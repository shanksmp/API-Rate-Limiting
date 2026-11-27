package com.api_rate_limiting.demo.support;

import com.api_rate_limiting.demo.ratelimit.Algorithm;
import com.api_rate_limiting.demo.ratelimit.Plan;

import java.util.UUID;

public final class TestPlans {

    private TestPlans() {
    }

    public static Plan tokenBucket(long capacity, double tokensPerSecond) {
        return new Plan("tb-test", Algorithm.TOKEN_BUCKET, capacity, tokensPerSecond / 1000.0, 0);
    }

    public static Plan slidingWindow(long limit, long windowMs) {
        return new Plan("sw-test", Algorithm.SLIDING_WINDOW, limit, 0, windowMs);
    }

    public static String subject() {
        return "test-" + UUID.randomUUID();
    }
}
