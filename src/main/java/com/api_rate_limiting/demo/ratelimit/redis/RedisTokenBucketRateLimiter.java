package com.api_rate_limiting.demo.ratelimit.redis;

import com.api_rate_limiting.demo.ratelimit.Algorithm;
import com.api_rate_limiting.demo.ratelimit.Plan;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = LuaRateLimitScripts.load("token_bucket.lua");

    private final StringRedisTemplate redis;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitDecision tryAcquire(String subject, Plan plan, int cost) {
        List<?> reply = redis.execute(SCRIPT,
                List.of(key(subject, plan)),
                Long.toString(plan.capacity()),
                Double.toString(plan.refillTokensPerMs()),
                Integer.toString(cost));
        return LuaRateLimitScripts.toDecision(reply, plan.capacity());
    }

    /** Includes the plan name, so changing a key's plan starts it on a fresh bucket. */
    public static String key(String subject, Plan plan) {
        return LuaRateLimitScripts.key(subject, "tb:" + plan.name());
    }
}
