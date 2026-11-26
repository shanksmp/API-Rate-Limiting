package com.api_rate_limiting.demo.ratelimit.redis;

import com.api_rate_limiting.demo.ratelimit.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision.Outcome;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

final class LuaRateLimitScripts {

    private LuaRateLimitScripts() {
    }

    @SuppressWarnings("rawtypes")
    static RedisScript<List> load(String file) {
        // Spring sends EVALSHA and falls back to EVAL on NOSCRIPT, so the script body is sent once
        return RedisScript.of(new ClassPathResource("scripts/" + file), List.class);
    }

    /**
     * All keys for one client share a {hash tag}, so they land in the same Redis Cluster slot.
     */
    static String key(String subject, String suffix) {
        return "rl:{" + subject + "}:" + suffix;
    }

    static RateLimitDecision toDecision(List<?> reply, long limit) {
        if (reply == null || reply.size() < 4) {
            throw new IllegalStateException("Unexpected rate limit script reply: " + reply);
        }
        boolean allowed = asLong(reply.get(0)) == 1;
        return new RateLimitDecision(
                allowed ? Outcome.ALLOWED : Outcome.DENIED,
                limit,
                asLong(reply.get(1)),
                asLong(reply.get(2)),
                asLong(reply.get(3)));
    }

    private static long asLong(Object o) {
        return ((Number) o).longValue();
    }
}
