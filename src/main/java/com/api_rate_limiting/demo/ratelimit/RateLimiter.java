package com.api_rate_limiting.demo.ratelimit;

/** One rate limiting algorithm. Implementations must be safe to call from many instances at once. */
public interface RateLimiter {

    Algorithm algorithm();

    /**
     * Atomically try to consume {@code cost} units for {@code subject}.
     *
     * @throws RuntimeException if the backing store is unavailable
     */
    RateLimitDecision tryAcquire(String subject, Plan plan, int cost);
}
