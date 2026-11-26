package com.api_rate_limiting.demo.ratelimit;

import com.api_rate_limiting.demo.ratelimit.redis.RedisSlidingWindowRateLimiter;
import com.api_rate_limiting.demo.ratelimit.redis.RedisTokenBucketRateLimiter;
import com.api_rate_limiting.demo.support.RedisTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.api_rate_limiting.demo.support.TestPlans.slidingWindow;
import static com.api_rate_limiting.demo.support.TestPlans.subject;
import static com.api_rate_limiting.demo.support.TestPlans.tokenBucket;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core guarantee: several app instances (each with its own Redis connection) hammering the
 * same key at the same instant never let through more than the limit.
 */
class DistributedConcurrencyIT {

    private static final int INSTANCES = 3;
    private static final int REQUESTS = 1_500;
    private static final int LIMIT = 50;

    @Test
    void tokenBucketAllowsExactlyCapacityAcrossInstances() throws Exception {
        List<RateLimiter> instances = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            instances.add(new RedisTokenBucketRateLimiter(RedisTestSupport.newTemplate()));
        }
        assertThat(race(instances, tokenBucket(LIMIT, 1.0 / 3600))).isEqualTo(LIMIT);
    }

    @Test
    void slidingWindowAllowsExactlyLimitAcrossInstances() throws Exception {
        List<RateLimiter> instances = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            instances.add(new RedisSlidingWindowRateLimiter(RedisTestSupport.newTemplate()));
        }
        assertThat(race(instances, slidingWindow(LIMIT, 3_600_000))).isEqualTo(LIMIT);
    }

    private static int race(List<RateLimiter> instances, Plan plan) throws Exception {
        String s = subject();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                RateLimiter limiter = instances.get(i % instances.size());
                futures.add(pool.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire(s, plan, 1).permitted()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return allowed.get();
    }
}
