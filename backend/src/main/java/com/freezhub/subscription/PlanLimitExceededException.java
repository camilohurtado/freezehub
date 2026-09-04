package com.freezhub.subscription;

/**
 * A creation refused by the plan rather than by validation or permission (FZ-081).
 *
 * <p>Answered as {@code 402 Payment Required}, which is the only status that says what
 * happened: the request was well-formed, so not {@code 400}; the caller is permitted, so
 * not {@code 403}; nothing conflicts, so not {@code 409}. The plan refused.
 *
 * <p>Carries the numbers so the response can say "10 of 10 applications used" rather than
 * "something went wrong" — a limit a customer cannot see is one they hit by surprise.
 */
public class PlanLimitExceededException extends RuntimeException {

    private final Plan plan;
    private final String resource;
    private final int limit;
    private final long current;

    public PlanLimitExceededException(Plan plan, String resource, int limit, long current) {
        super("The %s plan allows %d %s; this organization has %d."
                .formatted(plan, limit, resource, current));
        this.plan = plan;
        this.resource = resource;
        this.limit = limit;
        this.current = current;
    }

    public Plan plan() {
        return plan;
    }

    public String resource() {
        return resource;
    }

    public int limit() {
        return limit;
    }

    public long current() {
        return current;
    }
}
