package com.freezhub.policy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the deployment gate is deciding (FZ-062).
 *
 * <p>Counted separately by <em>why</em>, because the three mean very different things:
 * a block by a real freeze is the product working; a block for an unregistered name is
 * somebody's pipeline misconfigured — or somebody probing for a way through — and a
 * sudden rise in it is worth looking at rather than a graph going up.
 *
 * <p>Aggregate only, with no tenant tag: per-organization counters would grow a time
 * series per customer, and the trail (`FZ-060`) already answers the per-tenant question.
 */
@Component
public class PolicyMetrics {

    private final Counter allowed;
    private final Counter blockedByRestriction;
    private final Counter blockedUnregistered;

    public PolicyMetrics(MeterRegistry registry) {
        this.allowed = counter(registry, "allow", "restriction", "Deployment permitted");
        this.blockedByRestriction =
                counter(registry, "block", "restriction", "Blocked by a restriction in force");
        this.blockedUnregistered = counter(registry, "block", "unregistered",
                "Blocked because the application or environment is not registered");
    }

    private Counter counter(MeterRegistry registry, String decision, String reason, String description) {
        return Counter.builder("freezehub.policy.evaluations")
                .tag("decision", decision)
                .tag("reason", reason)
                .description(description)
                .register(registry);
    }

    public void allowed() {
        allowed.increment();
    }

    public void blockedByRestriction() {
        blockedByRestriction.increment();
    }

    public void blockedUnregistered() {
        blockedUnregistered.increment();
    }

}
