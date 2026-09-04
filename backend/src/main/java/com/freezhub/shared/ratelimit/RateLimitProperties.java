package com.freezhub.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How hard the limit bites, and where (FZ-087).
 *
 * <p>Configuration rather than constants: the right number is not knowable before there is
 * traffic, and finding it should not need a deployment of new code.
 */
@ConfigurationProperties(prefix = "freezehub.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** Requests per window, per client, across all limited paths together. */
    private int requests = 10;

    private Duration window = Duration.ofMinutes(1);

    /**
     * How many distinct clients to track before evicting.
     *
     * <p>A bound, not a tuning knob. The key is derived from something the caller
     * influences, so without a cap this map is a memory-exhaustion target.
     */
    private int maxTrackedClients = 10_000;

    /**
     * The paths this applies to.
     *
     * <p>Only unauthenticated endpoints. Everything else already requires a credential
     * that can be revoked, which is a better answer than a counter — and limiting an
     * authenticated API would throttle a customer's own pipeline.
     *
     * <p>{@code /api/signup} and {@code /api/demo-requests} do not exist yet ({@code
     * FZ-082}, {@code FZ-083}). They are listed now because this story exists to precede
     * them: {@code OI-11} makes rate limiting a prerequisite for shipping either, and a
     * default that has to be remembered later is one that gets forgotten.
     */
    private List<String> paths = List.of("/api/signup", "/api/demo-requests", "/api/dev/token");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequests() {
        return requests;
    }

    public void setRequests(int requests) {
        this.requests = requests;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }
}
