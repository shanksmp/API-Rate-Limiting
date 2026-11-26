package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import com.api_rate_limiting.demo.ratelimit.redis.RedisSlidingWindowRateLimiter;
import com.api_rate_limiting.demo.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static com.api_rate_limiting.demo.support.TestPlans.slidingWindow;
import static com.api_rate_limiting.demo.support.TestPlans.subject;
import static org.assertj.core.api.Assertions.assertThat;

class RedisSlidingWindowRateLimiterIT {

    static RedisSlidingWindowRateLimiter limiter;

    @BeforeAll
    static void setUp() {
        limiter = new RedisSlidingWindowRateLimiter(RedisTestSupport.newTemplate());
    }

    @Test
    void allowsLimitPerWindowThenDenies() {
        Plan plan = slidingWindow(5, 3_600_000);
        String s = subject();

        for (long expectedRemaining = 4; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitDecision d = limiter.tryAcquire(s, plan, 1);
            assertThat(d.outcome()).isEqualTo(Outcome.ALLOWED);
            assertThat(d.remaining()).isEqualTo(expectedRemaining);
        }

        RateLimitDecision denied = limiter.tryAcquire(s, plan, 1);
        assertThat(denied.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(denied.retryAfterMs()).isPositive();
    }

    @Test
    void previousWindowStillCountsJustAfterRollover() throws InterruptedException {
        long window = 400;
        Plan plan = slidingWindow(4, window);
        String s = subject();

        // Start right at the beginning of a window, fill it
        TimeUnit.MILLISECONDS.sleep(window - (System.currentTimeMillis() % window) + 5);
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire(s, plan, 1).permitted()).isTrue();
        }

        // ~10% into the next window, the previous window still weighs ~90%: 4 * 0.9 = 3.6 -> no room
        TimeUnit.MILLISECONDS.sleep(window - (System.currentTimeMillis() % window) + 40);
        assertThat(limiter.tryAcquire(s, plan, 1).permitted()).isFalse();
    }

    @Test
    void fullyResetsAfterTwoWindows() throws InterruptedException {
        long window = 200;
        Plan plan = slidingWindow(3, window);
        String s = subject();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(s, plan, 1);
        }
        assertThat(limiter.tryAcquire(s, plan, 1).permitted()).isFalse();

        TimeUnit.MILLISECONDS.sleep(window * 2 + 20);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(s, plan, 1).permitted()).isTrue();
        }
    }

    @Test
    void retryAfterIsAccurate() throws InterruptedException {
        long window = 1_000;
        Plan plan = slidingWindow(2, window);
        String s = subject();
        limiter.tryAcquire(s, plan, 1);
        limiter.tryAcquire(s, plan, 1);

        RateLimitDecision denied = limiter.tryAcquire(s, plan, 1);
        assertThat(denied.permitted()).isFalse();

        TimeUnit.MILLISECONDS.sleep(denied.retryAfterMs() + 20);
        assertThat(limiter.tryAcquire(s, plan, 1).permitted()).isTrue();
    }
}
