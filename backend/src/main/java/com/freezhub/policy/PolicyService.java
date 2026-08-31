package com.freezhub.policy;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.TeamApplication;
import com.freezhub.catalog.TeamApplicationRepository;
import com.freezhub.policy.PolicyEvaluationResponse.MatchedRestriction;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionStatus;
import com.freezhub.shared.security.ApiKeyPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether a deployment is currently allowed (FZ-051).
 *
 * <p>This is the enforcement boundary — the reason FreezeHub is a service rather than a
 * wiki page — so two properties matter more than anything else here:
 *
 * <ul>
 *   <li><strong>"In force" comes from the timestamps, never from {@code status}</strong>,
 *       which a reconciler maintains on an interval and which therefore lags (FZ-025).
 *       The query enforces this; see {@code findInForce}.</li>
 *   <li><strong>An unrecognised name blocks.</strong> Anything else leaves a bypass: a
 *       misspelt environment matches no scope list, so evaluating it normally would tend
 *       toward {@code ALLOW} and hand back a legitimate-looking permission to deploy in
 *       the middle of a freeze.</li>
 * </ul>
 */
@Service
public class PolicyService {

    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final ApplicationRepository applicationRepository;
    private final EnvironmentRepository environmentRepository;
    private final TeamApplicationRepository teamApplicationRepository;
    private final AuditTrail auditTrail;
    private final PolicyMetrics metrics;

    public PolicyService(ChangeRestrictionRepository changeRestrictionRepository,
                         ApplicationRepository applicationRepository,
                         EnvironmentRepository environmentRepository,
                         TeamApplicationRepository teamApplicationRepository,
                         AuditTrail auditTrail,
                         PolicyMetrics metrics) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.applicationRepository = applicationRepository;
        this.environmentRepository = environmentRepository;
        this.teamApplicationRepository = teamApplicationRepository;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
    }

    /**
     * Not {@code readOnly}: a refusal caused by an unregistered name writes an audit
     * entry (FZ-060), and it has to commit with the decision that produced it.
     */
    @Transactional
    public PolicyEvaluationResponse evaluate(Long organizationId, ApiKeyPrincipal caller,
                                             PolicyEvaluationRequest request, Instant now) {
        // Exact names. A near miss is a miss: the catalog's uniqueness is case-sensitive,
        // so treating "Prod" as "prod" here would make this endpoint disagree with the
        // registry it is reading from.
        Optional<Application> application =
                applicationRepository.findByOrganizationIdAndName(organizationId, request.application());
        Optional<Environment> environment =
                environmentRepository.findByOrganizationIdAndName(organizationId, request.environment());

        List<ScopeDimension> unregistered = new ArrayList<>();
        if (application.isEmpty()) {
            unregistered.add(ScopeDimension.APPLICATION);
        }
        if (environment.isEmpty()) {
            unregistered.add(ScopeDimension.ENVIRONMENT);
        }

        if (!unregistered.isEmpty()) {
            auditTrail.record(organizationId, AuditActor.of(caller),
                    AuditAction.POLICY_BLOCKED_UNREGISTERED, AuditResourceType.POLICY, null,
                    AuditDetails.builder()
                            .with("application", request.application())
                            .with("environment", request.environment())
                            .with("unregistered", unregistered.toString())
                            .toJson());

            metrics.blockedUnregistered();
            return blockUnregistered(request, now, unregistered);
        }

        Set<Long> applicationTeamIds = teamApplicationRepository
                .findAllByApplicationId(application.get().getId()).stream()
                .map(TeamApplication::getTeamId)
                .collect(Collectors.toSet());

        List<ChangeRestriction> matched = matching(
                organizationId, now, application.get().getId(), environment.get().getId(), applicationTeamIds);

        boolean blocked = matched.stream()
                .anyMatch(restriction -> restriction.getLevel() == RestrictionLevel.HARD_FREEZE);

        if (blocked) {
            metrics.blockedByRestriction();
        } else {
            metrics.allowed();
        }

        return new PolicyEvaluationResponse(
                blocked ? PolicyDecision.BLOCK : PolicyDecision.ALLOW,
                request.action(),
                request.application(),
                request.environment(),
                now,
                describe(blocked, matched),
                List.of(),
                matched.stream().map(MatchedRestriction::from).toList());
    }

    private List<ChangeRestriction> matching(Long organizationId, Instant now, Long applicationId,
                                             Long environmentId, Set<Long> applicationTeamIds) {
        List<ChangeRestriction> inForce = changeRestrictionRepository.findInForce(
                organizationId, now, RestrictionStatus.CANCELLED);

        // Explicit rather than left to lazy loading inside the transaction, so the fetch
        // stays deliberate. The collections are batch-fetched, so this is three queries
        // for the whole candidate set rather than three per restriction.
        inForce.forEach(restriction -> {
            Hibernate.initialize(restriction.getTeamIds());
            Hibernate.initialize(restriction.getApplicationIds());
            Hibernate.initialize(restriction.getEnvironmentIds());
        });

        return inForce.stream()
                .filter(restriction -> restriction.covers(applicationId, environmentId, applicationTeamIds))
                .toList();
    }

    /**
     * The decision for a deployment naming something FreezeHub does not know about.
     *
     * <p>Blocked outright, and it says which name it did not recognise — a refusal a
     * pipeline cannot act on is barely better than no refusal. The accepted cost is that
     * FreezeHub becomes a gate on catalog completeness: an application nobody has
     * registered cannot deploy at all, including when no freeze exists.
     *
     * <p>Returned as a {@code 200} carrying {@code BLOCK}, deliberately, rather than as a
     * {@code 4xx}. An error status lands in the pipeline's error branch, which is exactly
     * where `04-api.md` tells clients to choose fail-open or fail-closed for themselves —
     * so a fail-open pipeline would quietly convert this block back into a deployment.
     * A decision cannot be configured away.
     */
    private PolicyEvaluationResponse blockUnregistered(PolicyEvaluationRequest request, Instant now,
                                                       List<ScopeDimension> unregistered) {
        String detail = unregistered.stream()
                .map(dimension -> dimension == ScopeDimension.APPLICATION
                        ? "application '" + request.application() + "'"
                        : "environment '" + request.environment() + "'")
                .collect(Collectors.joining(" and "));

        return new PolicyEvaluationResponse(
                PolicyDecision.BLOCK,
                request.action(),
                request.application(),
                request.environment(),
                now,
                "Blocked: no " + detail + " is registered in this organization, so this deployment "
                        + "cannot be evaluated against the restrictions that may apply to it.",
                unregistered,
                List.of());
    }

    private String describe(boolean blocked, List<ChangeRestriction> matched) {
        if (matched.isEmpty()) {
            return "Allowed: no restriction is in force for this deployment.";
        }

        if (blocked) {
            String names = matched.stream()
                    .filter(restriction -> restriction.getLevel() == RestrictionLevel.HARD_FREEZE)
                    .map(ChangeRestriction::getName)
                    .collect(Collectors.joining(", "));
            return "Blocked by a change restriction in force: " + names + ".";
        }

        return "Allowed, but " + matched.size() + " advisory restriction"
                + (matched.size() == 1 ? " is" : "s are") + " in force for this deployment.";
    }

}
