package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * What the email sender actually hands to the mail server (FZ-042).
 *
 * <p>No Spring context: the sender is constructed with a {@link JavaMailSenderImpl}
 * subclass that captures the message instead of sending it, so the recipients, subject
 * and body are asserted rather than a stub standing in for them.
 */
class EmailNotificationSenderTest {

    private CapturingMailSender mailSender;
    private EmailNotificationSender sender;

    @BeforeEach
    void setUp() {
        mailSender = new CapturingMailSender();
        sender = new EmailNotificationSender(mailSender, "freezehub@acme.test");
    }

    private Integration destination(String config) {
        return new Integration(1L, IntegrationType.EMAIL, config);
    }

    private ChangeRestriction restriction() {
        return new ChangeRestriction(
                1L, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE,
                Instant.parse("2026-11-27T14:00:00Z"), Instant.parse("2026-12-02T09:30:00Z"),
                1L, Set.of(), Set.of(), Set.of());
    }

    private Notification notification(NotificationEvent event) {
        return new Notification(1L, 1L, 1L, event);
    }

    @Test
    void sendsToEveryConfiguredRecipient() {
        sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[\"releases@acme.test\",\"oncall@acme.test\"]}"));

        assertThat(mailSender.sent).hasSize(1);
        assertThat(mailSender.sent.getFirst().getTo())
                .containsExactly("releases@acme.test", "oncall@acme.test");
    }

    @Test
    void usesTheConfiguredFromAddress() {
        sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[\"a@acme.test\"]}"));

        assertThat(mailSender.sent.getFirst().getFrom()).isEqualTo("freezehub@acme.test");
    }

    @Test
    void putsTheHeadlineInTheSubjectAndTheDetailInTheBody() {
        // A subject that says only "FreezeHub notification" is useless in an inbox — the
        // reader needs to know what happened without opening it.
        sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[\"a@acme.test\"]}"));

        SimpleMailMessage message = mailSender.sent.getFirst();
        assertThat(message.getSubject())
                .contains("Black Friday Freeze")
                .contains("Deployments are blocked");
        assertThat(message.getText())
                .contains("Revenue-critical period")
                .contains("UTC");
    }

    @Test
    void saysTheSameThingEveryOtherChannelSays() {
        // Wording lives in NotificationMessage precisely so channels cannot diverge.
        Notification cancelled = notification(NotificationEvent.CANCELLED);
        sender.send(cancelled, restriction(), destination("{\"recipients\":[\"a@acme.test\"]}"));

        assertThat(mailSender.sent.getFirst().getText())
                .isEqualTo(NotificationMessage.body(NotificationEvent.CANCELLED, restriction()));
    }

    @Test
    void failsWhenTheMailServerRejectsTheMessage() {
        mailSender.failNext();

        assertThatThrownBy(() -> sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[\"a@acme.test\"]}")))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void keepsRecipientsOutOfTheFailureMessage() {
        // last_error is read by support; it should not become an address list.
        mailSender.failNext();

        assertThatThrownBy(() -> sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[\"private@acme.test\"]}")))
                .hasMessageNotContaining("private@acme.test");
    }

    @Test
    void failsClearlyWhenThereAreNoRecipients() {
        assertThatThrownBy(() -> sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("{\"recipients\":[]}")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("no recipients");
    }

    @Test
    void failsClearlyWhenTheConfigIsNotJson() {
        assertThatThrownBy(() -> sender.send(notification(NotificationEvent.ACTIVATED), restriction(),
                destination("nonsense")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void handlesTheChannelItDeclares() {
        assertThat(sender.type()).isEqualTo(IntegrationType.EMAIL);
    }

    /** Captures messages instead of opening an SMTP connection. */
    private static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<SimpleMailMessage> sent = new ArrayList<>();
        private boolean shouldFail;

        void failNext() {
            shouldFail = true;
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            if (shouldFail) {
                throw new MailSendException("simulated transport failure for private@acme.test");
            }
            sent.addAll(List.of(messages));
        }
    }

}
