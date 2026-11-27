package com.api_rate_limiting.demo.web;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFailureThrottleTest {

    private final AtomicLong now = new AtomicLong();
    private final AuthFailureThrottle throttle =
            new AuthFailureThrottle(new RateLimitProperties.AuthFailureThrottle(3, 1), now::get);

    @Test
    void blocksAfterBudgetIsSpent() {
        for (int i = 0; i < 3; i++) {
            assertThat(throttle.isBlocked("a")).isFalse();
            throttle.recordFailure("a");
        }
        assertThat(throttle.isBlocked("a")).isTrue();
        assertThat(throttle.retryAfterSeconds("a")).isEqualTo(1);
        assertThat(throttle.isBlocked("b")).isFalse();
    }

    @Test
    void recoversAtTheRefillRate() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("a");
        }
        now.addAndGet(999_000_000L);
        assertThat(throttle.isBlocked("a")).isTrue();
        now.addAndGet(2_000_000L);
        assertThat(throttle.isBlocked("a")).isFalse();
    }

    @Test
    void neverStoresMoreThanCapacity() {
        throttle.recordFailure("a");
        now.addAndGet(3_600_000_000_000L); // an hour later
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("a");
        }
        assertThat(throttle.isBlocked("a")).isTrue();
    }
}
