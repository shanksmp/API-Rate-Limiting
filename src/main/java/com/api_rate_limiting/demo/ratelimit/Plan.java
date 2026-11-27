package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.config.RateLimitProperties.PlanProperties;

import java.time.Duration;

/**
 * A validated, ready-to-use rate limit plan.
 *
 * @param refillTokensPerMs token bucket only
 * @param windowMs          sliding window only
 */
public record Plan(String name, Algorithm algorithm, long capacity, double refillTokensPerMs, long windowMs) {

    public static Plan from(String name, PlanProperties p) {
        return switch (p.algorithm()) {
            case TOKEN_BUCKET -> {
                if (p.refillTokens() == null || p.refillTokens() <= 0 || !isPositive(p.refillPeriod())) {
                    throw new IllegalArgumentException(
                            "Plan '" + name + "': token-bucket needs refill-tokens > 0 and refill-period > 0");
                }
                // The scripts work in milliseconds: a sub-millisecond period would become 0 ms and an infinite rate
                if (p.refillPeriod().toMillis() < 1) {
                    throw new IllegalArgumentException("Plan '" + name + "': refill-period must be at least 1ms");
                }
                double perMs = p.refillTokens() / (double) p.refillPeriod().toMillis();
                if (!Double.isFinite(perMs) || perMs <= 0) {
                    throw new IllegalArgumentException("Plan '" + name + "': refill rate is not a usable number");
                }
                yield new Plan(name, Algorithm.TOKEN_BUCKET, p.capacity(), perMs, 0);
            }
            case SLIDING_WINDOW -> {
                if (!isPositive(p.window()) || p.window().toMillis() < 100) {
                    throw new IllegalArgumentException("Plan '" + name + "': sliding-window needs window >= 100ms");
                }
                yield new Plan(name, Algorithm.SLIDING_WINDOW, p.capacity(), 0, p.window().toMillis());
            }
        };
    }

    /** Window advertised in the RateLimit-Policy header: time to fully refill, or the window length. */
    public long policyWindowSeconds() {
        long ms = algorithm == Algorithm.TOKEN_BUCKET
                ? (long) Math.ceil(capacity / refillTokensPerMs)
                : windowMs;
        return Math.max(1, (ms + 999) / 1000);
    }

    private static boolean isPositive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }
}
