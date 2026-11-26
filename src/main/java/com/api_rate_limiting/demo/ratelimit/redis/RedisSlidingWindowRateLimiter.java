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
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = LuaRateLimitScripts.load("sliding_window.lua");

    private final StringRedisTemplate redis;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW;
    }

    @Override
    public RateLimitDecision tryAcquire(String subject, Plan plan, int cost) {
        List<?> reply = redis.execute(SCRIPT,
                List.of(key(subject, plan)),
                Long.toString(plan.capacity()),
                Long.toString(plan.windowMs()),
                Integer.toString(cost));
        return LuaRateLimitScripts.toDecision(reply, plan.capacity());
    }

    /** Includes the plan name, so changing a key's plan starts it on a fresh bucket. */
    public static String key(String subject, Plan plan) {
        return LuaRateLimitScripts.key(subject, "sw:" + plan.name());
    }
}
