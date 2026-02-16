package com.api_rate_limiting.demo.ratelimit.v3;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
    @Service
public class RedisTokenBucketRateLimiter {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    private static final String LUA_TOKEN_BUCKET = """
            local key_tokens = KEYS[1]
            local key_ts = KEYS[2]
            
            local capacity = tonumber(ARGV[1])
            local refill_ms = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local ttl_sec = tonumber(ARGV[5])
            
            local tokens = tonumber(redis.call('GET',key_tokens))
            local ts = tonumber(redis.call('GET',key_ts))
            
            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now_ms end
            
            --refill
            local elapsed = now_ms - ts
            local add = math.floor(elapsed / refill_ms)
            if add > 0 then
                tokens = math.min(capacity, tokens + add)
                ts = ts + add * refill_ms
            end
            
            local allowed = 0
            local retry_after_ms = 0
            
            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
            else
                local ms_until_next = refill_ms - (now_ms - ts)
                if ms_until_next < 0 then ms_until_next = 0 end
                retry_after_ms = ms_until_next
            end
            
            redis.call('SET', key_tokens, tokens, 'EX', ttl_sec)
            redis.call('SET', key_ts, ts, 'EX', ttl_sec)
            
            return {allowed, tokens, retry_after_ms}
            
            """;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis){
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(LUA_TOKEN_BUCKET);
        this.script.setResultType(List.class);
    }


    public RateLimitDecision check(String apiKey){
        long now = System.currentTimeMillis();

        String keyTokens = "rl:v3:tb:" + apiKey + ":t";
        String keyTs = "rl:v3:tb:" + apiKey + ":ts";

        @SuppressWarnings("unchecked")
        List <Long> res = (
                List<Long>) redis.execute(
                script,
                List.of(keyTokens, keyTs),
                String.valueOf(RateLimitConfig.CAPACITY),
                String.valueOf(RateLimitConfig.REFILL_INTERVAL_MS),
                String.valueOf(now),
                "1",
                String.valueOf(RateLimitConfig.REDIS_TTL_SECONDS)
        );

        // Defensive check (prevents NPE if Redis is down)

        if(res == null || res.size()<3){
            //fail-open or fail-closed. for the purpose of demo project and availability going with fail-open

            return RateLimitDecision.allowed(RateLimitConfig.CAPACITY);
        }

        long allowed = res.get(0);
        long remaining = res.get(1);
        long retryAfterMs = res.get(2);

        if(allowed == 1){
            return RateLimitDecision.allowed(remaining);
        }

        long retryAfterSeconds = Math.max(1, (retryAfterMs + 999)/1000);
        return RateLimitDecision.denied(remaining, retryAfterSeconds);
    }

}
