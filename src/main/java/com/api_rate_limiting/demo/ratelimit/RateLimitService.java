package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.config.RateLimitProperties.FailMode;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Front door for rate limit checks: picks the algorithm for the plan, protects Redis with a
 * circuit breaker, applies the fail-open/fail-closed policy and records metrics.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final Map<Algorithm, RateLimiter> limiters = new EnumMap<>(Algorithm.class);
    private final CircuitBreaker breaker;
    private final FailMode failMode;
    private final MeterRegistry meters;

    public RateLimitService(List<RateLimiter> limiters,
                            @Qualifier("rateLimiterCircuitBreaker") CircuitBreaker rateLimiterCircuitBreaker,
                            RateLimitProperties props,
                            MeterRegistry meters) {
        limiters.forEach(l -> this.limiters.put(l.algorithm(), l));
        this.breaker = rateLimiterCircuitBreaker;
        this.failMode = props.failMode();
        this.meters = meters;
    }

    public RateLimitDecision check(String subject, Plan plan, int cost) {
        if (cost < 1 || cost > plan.capacity()) {
            throw new IllegalArgumentException("cost must be between 1 and the plan capacity");
        }
        RateLimiter limiter = limiters.get(plan.algorithm());
        if (limiter == null) {
            throw new IllegalStateException("No limiter registered for " + plan.algorithm());
        }

        long start = System.nanoTime();
        RateLimitDecision decision;
        try {
            decision = breaker.executeSupplier(() -> limiter.tryAcquire(subject, plan, cost));
        } catch (CallNotPermittedException e) {
            // Breaker is open: skip Redis entirely instead of waiting for a timeout
            decision = unavailable(plan);
        } catch (RuntimeException e) {
            log.debug("Rate limit check failed for plan {}", plan.name(), e);
            decision = unavailable(plan);
        }

        Timer.builder("ratelimiter.check")
                .description("Time to make a rate limit decision")
                .tag("algorithm", plan.algorithm().name().toLowerCase())
                .tag("outcome", decision.outcome().name().toLowerCase())
                .publishPercentileHistogram()
                .register(meters)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        Counter.builder("ratelimiter.decisions")
                .description("Rate limit decisions by plan and outcome")
                .tag("plan", plan.name())
                .tag("outcome", decision.outcome().name().toLowerCase())
                .register(meters)
                .increment();
        return decision;
    }

    private RateLimitDecision unavailable(Plan plan) {
        return RateLimitDecision.unavailable(
                failMode == FailMode.OPEN ? Outcome.FAIL_OPEN : Outcome.FAIL_CLOSED, plan.capacity());
    }
}
