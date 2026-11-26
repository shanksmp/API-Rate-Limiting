package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import com.api_rate_limiting.demo.ratelimit.redis.RedisTokenBucketRateLimiter;
import com.api_rate_limiting.demo.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static com.api_rate_limiting.demo.support.TestPlans.subject;
import static com.api_rate_limiting.demo.support.TestPlans.tokenBucket;
import static org.assertj.core.api.Assertions.assertThat;

class RedisTokenBucketRateLimiterIT {

    static StringRedisTemplate redis;
    static RedisTokenBucketRateLimiter limiter;

    @BeforeAll
    static void setUp() {
        redis = RedisTestSupport.newTemplate();
        limiter = new RedisTokenBucketRateLimiter(redis);
    }

    @Test
    void allowsBurstUpToCapacityThenDenies() {
        Plan plan = tokenBucket(5, 1.0 / 3600); // 1 token per hour
        String s = subject();

        for (long expectedRemaining = 4; expectedRemaining >= 0; expectedRemaining--) {
            RateLimitDecision d = limiter.tryAcquire(s, plan, 1);
            assertThat(d.outcome()).isEqualTo(Outcome.ALLOWED);
            assertThat(d.remaining()).isEqualTo(expectedRemaining);
            assertThat(d.limit()).isEqualTo(5);
        }

        RateLimitDecision denied = limiter.tryAcquire(s, plan, 1);
        assertThat(denied.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs()).isBetween(3_590_000L, 3_600_000L);
    }

    @Test
    void refillsContinuouslyNotInSteps() throws InterruptedException {
        Plan plan = tokenBucket(5, 20); // one token every 50ms
        String s = subject();
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(s, plan, 1);
        }
        assertThat(limiter.tryAcquire(s, plan, 1).outcome()).isEqualTo(Outcome.DENIED);

        TimeUnit.MILLISECONDS.sleep(130); // ~2.6 tokens

        assertThat(limiter.tryAcquire(s, plan, 1).outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(limiter.tryAcquire(s, plan, 1).outcome()).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void neverRefillsBeyondCapacity() throws InterruptedException {
        Plan plan = tokenBucket(3, 1000);
        String s = subject();
        limiter.tryAcquire(s, plan, 1);
        TimeUnit.MILLISECONDS.sleep(50); // would add 50 tokens without the cap

        RateLimitDecision d = limiter.tryAcquire(s, plan, 1);
        assertThat(d.remaining()).isEqualTo(2);
    }

    @Test
    void weightedCostConsumesMultipleTokens() {
        Plan plan = tokenBucket(10, 1.0 / 3600);
        String s = subject();

        assertThat(limiter.tryAcquire(s, plan, 4).remaining()).isEqualTo(6);
        assertThat(limiter.tryAcquire(s, plan, 4).remaining()).isEqualTo(2);
        RateLimitDecision d = limiter.tryAcquire(s, plan, 4);
        assertThat(d.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(d.remaining()).isEqualTo(2); // a denied request takes nothing
    }

    @Test
    void subjectsHaveIndependentBuckets() {
        Plan plan = tokenBucket(1, 1.0 / 3600);
        String a = subject();
        String b = subject();

        assertThat(limiter.tryAcquire(a, plan, 1).permitted()).isTrue();
        assertThat(limiter.tryAcquire(a, plan, 1).permitted()).isFalse();
        assertThat(limiter.tryAcquire(b, plan, 1).permitted()).isTrue();
    }

    @Test
    void bucketKeyExpiresOnceItWouldBeFull() {
        Plan plan = tokenBucket(10, 1); // empty -> full takes 10s
        String s = subject();
        limiter.tryAcquire(s, plan, 5);  // 5s until full

        Long ttlMs = redis.getExpire(RedisTokenBucketRateLimiter.key(s, plan), TimeUnit.MILLISECONDS);
        assertThat(ttlMs).isBetween(5_000L, 6_100L);
    }

    @Test
    void keyUsesClusterHashTag() {
        assertThat(RedisTokenBucketRateLimiter.key("key_abc", tokenBucket(1, 1))).isEqualTo("rl:{key_abc}:tb:tb-test");
    }
}
