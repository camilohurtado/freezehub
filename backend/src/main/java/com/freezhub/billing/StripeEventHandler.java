package com.freezhub.billing;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionRepository;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a verified Stripe event to a subscription (FZ-084).
 *
 * <p><strong>This is the only thing that changes entitlement.</strong> Not the Checkout
 * redirect, which is a browser navigation anyone can type; not a client claiming it just
 * paid. Stripe is the source of truth for payment, FreezeHub is the source of truth for
 * entitlement, and a signature-verified delivery is the only bridge between them.
 *
 * <p>An event whose organization cannot be identified is recorded and ignored rather than
 * failing: returning an error would make Stripe redeliver it for ever, and the thing it
 * would be retrying is a lookup that will never succeed.
 */
@Service
public class StripeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StripeEventHandler.class);

    /** Set on the Checkout session so the plan does not have to be inferred from a price. */
    static final String ORGANIZATION_METADATA_KEY = "freezehub_organization_id";
    static final String PLAN_METADATA_KEY = "freezehub_plan";

    private final SubscriptionRepository subscriptions;
    private final StripeEventRepository events;
    /**
     * Optional on purpose: {@link BillingNotifier} only exists when a from-address is
     * configured. An unconfigured mail server must not stop payment events being
     * processed — moving the subscription to PAST_DUE is the half that matters, and
     * failing here would make Stripe redeliver the event instead of sending the mail.
     */
    private final ObjectProvider<BillingNotifier> notifier;
    private final AuditTrail auditTrail;

    public StripeEventHandler(SubscriptionRepository subscriptions, StripeEventRepository events,
                              ObjectProvider<BillingNotifier> notifier, AuditTrail auditTrail) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.notifier = notifier;
        this.auditTrail = auditTrail;
    }

    /**
     * @return whether this delivery was acted on; {@code false} means it had been seen before
     */
    @Transactional
    public boolean handle(Event event) {
        if (!claim(event)) {
            log.info("Stripe event {} has already been processed; ignoring redelivery", event.getId());
            return false;
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> onCheckoutCompleted(event);
            case "customer.subscription.updated" -> onSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(event);
            case "invoice.payment_failed" -> onPaymentFailed(event);
            // Stripe sends a great many event types and will send more over time. An
            // unrecognised one is a 200 with nothing done, not an error: answering
            // anything else makes Stripe retry something we will never handle.
            default -> log.debug("Stripe event {} of type {} needs no action",
                    event.getId(), event.getType());
        }
        return true;
    }

    /**
     * Records the event id, or reports that it was already there.
     *
     * <p>A check and then an insert, rather than an insert and a caught constraint
     * violation. Catching it does not help: inside a transaction the violation has already
     * marked it rollback-only, so the request fails anyway — as an
     * {@code UnexpectedRollbackException}, which is a worse error than the one it replaced.
     *
     * <p>The primary key is still the real guarantee. If two redeliveries genuinely race,
     * one of them violates it and that request fails with a 500 — Stripe then redelivers,
     * the check finds the row, and it is absorbed. A failed request that resolves on retry
     * is the right outcome for a race; a silently double-applied event is not.
     */
    private boolean claim(Event event) {
        if (events.existsById(event.getId())) {
            return false;
        }
        events.save(new StripeEvent(event.getId(), event.getType(), now()));
        return true;
    }

    private void onCheckoutCompleted(Event event) {
        Optional<Session> session = dataObject(event, Session.class);
        if (session.isEmpty()) {
            log.warn("Stripe event {} carried no readable checkout session", event.getId());
            return;
        }

        Session checkout = session.get();
        Optional<Long> organizationId = organizationFrom(checkout.getMetadata() == null
                ? null : checkout.getMetadata().get(ORGANIZATION_METADATA_KEY), event);
        Optional<Plan> plan = planFrom(checkout.getMetadata() == null
                ? null : checkout.getMetadata().get(PLAN_METADATA_KEY), event);
        if (organizationId.isEmpty() || plan.isEmpty()) {
            return;
        }

        subscriptionFor(organizationId.get(), event).ifPresent(subscription -> {
            Plan previous = subscription.getPlan();
            subscription.activate(plan.get(), checkout.getCustomer(), checkout.getSubscription(), null);
            auditTrail.record(subscription.getOrganizationId(), AuditActor.system(),
                    AuditAction.SUBSCRIPTION_PLAN_CHANGED, AuditResourceType.ORGANIZATION,
                    subscription.getOrganizationId(),
                    AuditDetails.builder()
                            .with("from", previous.name())
                            .with("to", plan.get().name())
                            .with("via", "stripe")
                            .toJson());
            log.info("Organization {} is now ACTIVE on {}", subscription.getOrganizationId(), plan.get());
        });
    }

    private void onSubscriptionUpdated(Event event) {
        bySubscriptionId(event).ifPresent(subscription -> {
            // Only the period end is taken here. A plan change arrives through Checkout,
            // where the metadata says unambiguously what was bought; inferring it from a
            // price on every update is a way to move a customer onto a plan nobody sold
            // them when a price id is renamed.
            log.info("Stripe reported an update for organization {}", subscription.getOrganizationId());
        });
    }

    private void onSubscriptionDeleted(Event event) {
        bySubscriptionId(event).ifPresent(subscription -> {
            subscription.cancel();
            auditTrail.record(subscription.getOrganizationId(), AuditActor.system(),
                    AuditAction.SUBSCRIPTION_CANCELLED, AuditResourceType.ORGANIZATION,
                    subscription.getOrganizationId());
            log.info("Subscription cancelled for organization {}", subscription.getOrganizationId());
        });
    }

    private void onPaymentFailed(Event event) {
        byCustomerId(event).ifPresent(subscription -> {
            subscription.markPastDue();
            auditTrail.record(subscription.getOrganizationId(), AuditActor.system(),
                    AuditAction.SUBSCRIPTION_PAYMENT_FAILED, AuditResourceType.ORGANIZATION,
                    subscription.getOrganizationId());
            notifier.ifAvailable(billing -> billing.paymentFailed(subscription.getOrganizationId()));
            log.warn("Payment failed for organization {}; marked PAST_DUE",
                    subscription.getOrganizationId());
        });
    }

    private Optional<Long> organizationFrom(String raw, Event event) {
        if (raw == null || raw.isBlank()) {
            log.warn("Stripe event {} carried no organization metadata", event.getId());
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException notANumber) {
            log.warn("Stripe event {} carried an unreadable organization id", event.getId());
            return Optional.empty();
        }
    }

    private Optional<Plan> planFrom(String raw, Event event) {
        if (raw == null || raw.isBlank()) {
            log.warn("Stripe event {} carried no plan metadata", event.getId());
            return Optional.empty();
        }
        try {
            return Optional.of(Plan.valueOf(raw));
        } catch (IllegalArgumentException unknownPlan) {
            log.warn("Stripe event {} named a plan that does not exist", event.getId());
            return Optional.empty();
        }
    }

    private Optional<Subscription> subscriptionFor(Long organizationId, Event event) {
        Optional<Subscription> found = subscriptions.findByOrganizationId(organizationId);
        if (found.isEmpty()) {
            log.warn("Stripe event {} referenced organization {}, which has no subscription",
                    event.getId(), organizationId);
        }
        return found;
    }

    private Optional<Subscription> bySubscriptionId(Event event) {
        return dataObject(event, com.stripe.model.Subscription.class)
                .flatMap(stripeSubscription ->
                        subscriptions.findByStripeSubscriptionId(stripeSubscription.getId()));
    }

    private Optional<Subscription> byCustomerId(Event event) {
        return dataObject(event, com.stripe.model.Invoice.class)
                .flatMap(invoice -> subscriptions.findByStripeCustomerId(invoice.getCustomer()));
    }

    /**
     * The event's payload, tolerating an API version that is not the SDK's.
     *
     * <p>{@code getObject()} returns empty whenever the event was produced by a different
     * Stripe API version than this SDK pins — which happens in production every time the
     * account's version and the library drift apart, not only in tests. Silently doing
     * nothing in that case would mean a customer paying and never being activated, so the
     * documented escape hatch is used and only a genuinely unreadable payload is skipped.
     */
    private <T extends com.stripe.model.StripeObject> Optional<T> dataObject(Event event, Class<T> type) {
        var deserializer = event.getDataObjectDeserializer();
        Optional<com.stripe.model.StripeObject> object = deserializer.getObject();
        if (object.isEmpty()) {
            try {
                object = Optional.ofNullable(deserializer.deserializeUnsafe());
            } catch (com.stripe.exception.EventDataObjectDeserializationException unreadable) {
                log.warn("Stripe event {} carried a payload this version cannot read", event.getId());
                return Optional.empty();
            }
        }
        return object.filter(type::isInstance).map(type::cast);
    }

    Instant now() {
        return Instant.now();
    }
}
