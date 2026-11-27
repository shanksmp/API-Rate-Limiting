package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.config.RateLimitProperties.PlanProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    @Test
    void tokenBucketConvertsRefillToTokensPerMs() {
        Plan p = Plan.from("free", new PlanProperties(Algorithm.TOKEN_BUCKET, 10, 10L, Duration.ofMinutes(1), null));
        assertThat(p.refillTokensPerMs()).isEqualTo(10 / 60_000.0);
        assertThat(p.policyWindowSeconds()).isEqualTo(60);
    }

    @Test
    void slidingWindowUsesWindowLength() {
        Plan p = Plan.from("p", new PlanProperties(Algorithm.SLIDING_WINDOW, 100, null, null, Duration.ofSeconds(30)));
        assertThat(p.windowMs()).isEqualTo(30_000);
        assertThat(p.policyWindowSeconds()).isEqualTo(30);
    }

    @Test
    void rejectsIncompleteTokenBucket() {
        assertThatThrownBy(() -> Plan.from("bad", new PlanProperties(Algorithm.TOKEN_BUCKET, 10, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad");
    }

    @Test
    void rejectsTinyWindow() {
        assertThatThrownBy(() -> Plan.from("bad",
                new PlanProperties(Algorithm.SLIDING_WINDOW, 10, null, null, Duration.ofMillis(10))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
