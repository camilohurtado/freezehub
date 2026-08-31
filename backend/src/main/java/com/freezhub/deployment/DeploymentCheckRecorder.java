package com.freezhub.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.policy.PolicyDecision;
import com.freezhub.shared.security.ApiKeyPrincipal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the record of a check (FZ-070).
 *
 * <p>{@code Propagation.MANDATORY}, like {@code AuditTrail} and {@code NotificationOutbox}:
 * the record commits with the decision it describes, so the console can never show an
 * answer that was never given.
 */
@Service
public class DeploymentCheckRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeploymentCheckRepository deploymentCheckRepository;

    public DeploymentCheckRecorder(DeploymentCheckRepository deploymentCheckRepository) {
        this.deploymentCheckRepository = deploymentCheckRepository;
    }

    /**
     * @param matched   the restrictions that decided it, denormalised so a later rename
     *                  cannot rewrite this record
     * @param metadata  what the caller chose to tell us; every field may be null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ApiKeyPrincipal caller, String application, String environment,
                       PolicyDecision decision, BlockedReason blockedReason,
                       List<MatchedRestriction> matched, CheckMetadata metadata) {
        deploymentCheckRepository.save(new DeploymentCheck(
                caller.organizationId(),
                caller.apiKeyId(),
                caller.name(),
                application,
                environment,
                decision,
                blockedReason,
                serialise(matched),
                metadata.actor(),
                metadata.reference(),
                metadata.source()));
    }

    private String serialise(List<MatchedRestriction> matched) {
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(matched);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialise matched restrictions", impossible);
        }
    }

    /** A restriction as it was when the check happened. */
    public record MatchedRestriction(Long id, String name, String level) {
    }

    /**
     * What the caller volunteered about the deployment.
     *
     * <p>All optional: not every runner exposes them, and a pipeline written before this
     * existed has to keep working unchanged.
     */
    public record CheckMetadata(String actor, String reference, String source) {

        public static final CheckMetadata NONE = new CheckMetadata(null, null, null);
    }

}
