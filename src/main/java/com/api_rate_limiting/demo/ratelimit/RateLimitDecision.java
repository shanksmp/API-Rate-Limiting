package com.api_rate_limiting.demo.ratelimit;

/**
 * @param limit        plan capacity
 * @param remaining    requests left right now (-1 when unknown because Redis is unavailable)
 * @param retryAfterMs when denied: how long until the request would succeed
 * @param resetMs      how long until the limit is fully restored
 */
public record RateLimitDecision(Outcome outcome, long limit, long remaining, long retryAfterMs, long resetMs) {

    public enum Outcome {
        ALLOWED,
        DENIED,
        /** Redis unavailable, request let through (fail-open). */
        FAIL_OPEN,
        /** Redis unavailable, request rejected (fail-closed). */
        FAIL_CLOSED
    }

    public boolean permitted() {
        return outcome == Outcome.ALLOWED || outcome == Outcome.FAIL_OPEN;
    }

    public boolean degraded() {
        return outcome == Outcome.FAIL_OPEN || outcome == Outcome.FAIL_CLOSED;
    }

    public static RateLimitDecision unavailable(Outcome outcome, long limit) {
        return new RateLimitDecision(outcome, limit, -1, 0, 0);
    }
}
