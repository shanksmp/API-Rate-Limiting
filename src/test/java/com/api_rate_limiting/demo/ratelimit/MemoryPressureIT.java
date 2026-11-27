package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import com.api_rate_limiting.demo.ratelimit.redis.RedisTokenBucketRateLimiter;
import com.api_rate_limiting.demo.support.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static com.api_rate_limiting.demo.support.TestPlans.subject;
import static com.api_rate_limiting.demo.support.TestPlans.tokenBucket;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding 3: when Redis runs out of memory, a client must never silently get a fresh quota.
 * Uses the same eviction policy as docker-compose.yml.
 */
class MemoryPressureIT {

    static final String POLICY = "noeviction";

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(RedisTestSupport.REDIS_IMAGE)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--maxmemory", "4mb", "--maxmemory-policy", POLICY);

    static StringRedisTemplate redis;

    @BeforeAll
    static void start() {
        REDIS.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void stop() {
        REDIS.stop();
    }

    @Test
    void fullRedisNeverHandsOutAFreshBucket() {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis);
        Plan plan = tokenBucket(1, 1.0 / 3600);
        String s = subject();
        assertThat(limiter.tryAcquire(s, plan, 1).outcome()).isEqualTo(Outcome.ALLOWED);

        // Fill Redis well past maxmemory with long-lived junk
        String value = "x".repeat(10_000);
        for (int i = 0; i < 800; i++) {
            try {
                redis.opsForValue().set("junk:" + i, value, Duration.ofDays(1));
            } catch (DataAccessException oom) {
                break; // noeviction: writes are refused once memory is full
            }
        }

        Outcome outcome;
        try {
            outcome = limiter.tryAcquire(s, plan, 1).outcome();
        } catch (DataAccessException e) {
            outcome = null; // failing loudly is fine: RateLimitService then applies the fail mode
        }
        assertThat(outcome).as("bucket was drained, so the request must not be allowed").isNotEqualTo(Outcome.ALLOWED);
    }
}
