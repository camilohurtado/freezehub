package com.freezhub.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How hard each limit bites, and where (FZ-087, FZ-130).
 *
 * <p>Configuration rather than constants: the right number is not knowable before there is
 * traffic, and finding it should not need a deployment of new code.
 *
 * <p>Four limits rather than one, because they are protecting different things and a
 * single number cannot be right for all of them. Signup is a form a person fills in once;
 * the Policy API is a machine asking on every deployment. A limit generous enough for the
 * second would be no limit at all on the first.
 */
@ConfigurationProperties(prefix = "freezehub.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /**
     * How many distinct clients to track before evicting, shared by every limit.
     *
     * <p>A bound, not a tuning knob. The key is derived from something the caller
     * influences, so without a cap these maps are a memory-exhaustion target.
     */
    private int maxTrackedClients = 10_000;

    /**
     * The endpoints reachable without any credential (`FZ-087`).
     *
     * <p>{@code /api/signup} and {@code /api/demo-requests} were listed here before they
     * existed ({@code FZ-082}, {@code FZ-083}): {@code OI-11} made rate limiting a
     * prerequisite for shipping either, and a default that has to be remembered later is
     * one that gets forgotten.
     */
    private PathLimit unauthenticated = new PathLimit(10, Duration.ofMinutes(1),
            List.of("/api/signup", "/api/demo-requests", "/api/dev/token"));

    /**
     * The Stripe webhook (`FZ-130`).
     *
     * <p>Unauthenticated in the credential sense — the signature is what makes a delivery
     * trustworthy — so anyone can make us verify one, and verification is work. Limited
     * generously because Stripe genuinely bursts: a plan change can fan out several events
     * at once, and a customer's first subscription arrives as a small storm.
     *
     * <p>A {@code 429} here is safe in a way it is almost nowhere else, which is why the
     * limit can be enforced without hedging: <strong>Stripe retries</strong>, for up to
     * three days. A refused delivery is delayed, not lost.
     */
    private PathLimit stripeWebhook = new PathLimit(120, Duration.ofMinutes(1),
            List.of("/api/webhooks/stripe/**"));

    /** The two limits on {@code /api/policy/**} (`FZ-130`). */
    private Policy policy = new Policy();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }

    public PathLimit getUnauthenticated() {
        return unauthenticated;
    }

    public void setUnauthenticated(PathLimit unauthenticated) {
        this.unauthenticated = unauthenticated;
    }

    public PathLimit getStripeWebhook() {
        return stripeWebhook;
    }

    public void setStripeWebhook(PathLimit stripeWebhook) {
        this.stripeWebhook = stripeWebhook;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }

    /**
     * The Policy API, limited twice on two different keys (`FZ-130`).
     *
     * <p>Neither of these has a path list: the machine security chain is what defines the
     * path, and a limit that could be pointed somewhere else by configuration would be a
     * way to switch it off by accident.
     */
    public static class Policy {

        /**
         * Requests that arrive without a usable API key, counted per source address.
         *
         * <p>Not credential protection — a 256-bit key is not guessable, and this would be
         * a poor defence if it were. It is the cost of answering: every attempt is a hash
         * and a database lookup, and nothing else stops one source repeating that.
         */
        private Limit failures = new Limit(30, Duration.ofMinutes(1));

        /**
         * Authenticated evaluations, counted per API key.
         *
         * <p><strong>The number is chosen against a failure that is worse than the abuse
         * it prevents.</strong> {@code freeze-check.sh} fails closed by default (`FREEZEHUB_ON_ERROR=block`, `FZ-053`), so a
         * {@code 429} to a legitimate pipeline does not slow a deployment down — it stops
         * it, and the customer sees FreezeHub break their delivery. 60 in 10 seconds is
         * six a second sustained, which no real pipeline approaches even when a monorepo
         * fans out fifty deployments at once.
         *
         * <p>The <em>window</em> is deliberately short rather than the limit being large.
         * The two allow the same rate, but a ten-second window can never answer
         * {@code Retry-After: 54} — so the worst a refused pipeline can be asked to wait is
         * ten seconds, which the connector now sits out and retries rather than failing
         * the build.
         */
        private Limit perKey = new Limit(60, Duration.ofSeconds(10));

        public Limit getFailures() {
            return failures;
        }

        public void setFailures(Limit failures) {
            this.failures = failures;
        }

        public Limit getPerKey() {
            return perKey;
        }

        public void setPerKey(Limit perKey) {
            this.perKey = perKey;
        }
    }

    /** Requests per window, per client. */
    public static class Limit {

        private int requests;
        private Duration window;

        Limit(int requests, Duration window) {
            this.requests = requests;
            this.window = window;
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
    }

    /** A limit applied by path, through an interceptor. */
    public static class PathLimit extends Limit {

        private List<String> paths;

        PathLimit(int requests, Duration window, List<String> paths) {
            super(requests, window);
            this.paths = paths;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }
    }
}
