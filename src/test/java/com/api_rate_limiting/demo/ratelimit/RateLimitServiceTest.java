package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.config.RateLimitProperties.FailMode;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import com.api_rate_limiting.demo.support.TestProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.api_rate_limiting.demo.support.TestPlans.tokenBucket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Plan plan = tokenBucket(10, 1);

    @Test
    void passesThroughLimiterDecision() {
        RateLimitService service = service(new FakeLimiter(false), FailMode.OPEN, breaker());
        assertThat(service.check("s", plan, 1).outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(meters.get("ratelimiter.decisions").tag("outcome", "allowed").counter().count()).isEqualTo(1);
        assertThat(meters.get("ratelimiter.check").timer().count()).isEqualTo(1);
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        RateLimitService service = service(new FakeLimiter(true), FailMode.OPEN, breaker());
        RateLimitDecision d = service.check("s", plan, 1);
        assertThat(d.outcome()).isEqualTo(Outcome.FAIL_OPEN);
        assertThat(d.permitted()).isTrue();
    }

    @Test
    void failsClosedWhenConfigured() {
        RateLimitService service = service(new FakeLimiter(true), FailMode.CLOSED, breaker());
        RateLimitDecision d = service.check("s", plan, 1);
        assertThat(d.outcome()).isEqualTo(Outcome.FAIL_CLOSED);
        assertThat(d.permitted()).isFalse();
    }

    @Test
    void openBreakerStopsCallingRedis() {
        FakeLimiter limiter = new FakeLimiter(true);
        CircuitBreaker breaker = breaker();
        RateLimitService service = service(limiter, FailMode.OPEN, breaker);

        for (int i = 0; i < 50; i++) {
            service.check("s", plan, 1);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(limiter.calls.get()).isEqualTo(10); // minimumNumberOfCalls, then short-circuited
    }

    @Test
    void rejectsCostAboveCapacity() {
        RateLimitService service = service(new FakeLimiter(false), FailMode.OPEN, breaker());
        assertThatThrownBy(() -> service.check("s", plan, 11)).isInstanceOf(IllegalArgumentException.class);
    }

    private RateLimitService service(RateLimiter limiter, FailMode mode, CircuitBreaker breaker) {
        RateLimitProperties props = TestProperties.withFailMode(mode);
        return new RateLimitService(List.of(limiter), breaker, props, meters);
    }

    private static CircuitBreaker breaker() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    }

    static final class FakeLimiter implements RateLimiter {
        final AtomicInteger calls = new AtomicInteger();
        final boolean fail;

        FakeLimiter(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Algorithm algorithm() {
            return Algorithm.TOKEN_BUCKET;
        }

        @Override
        public RateLimitDecision tryAcquire(String subject, Plan plan, int cost) {
            calls.incrementAndGet();
            if (fail) {
                throw new RedisConnectionFailureException("down");
            }
            return new RateLimitDecision(Outcome.ALLOWED, plan.capacity(), plan.capacity() - cost, 0, 0);
        }
    }
}
