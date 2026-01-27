package com.api_rate_limiting.demo.ratelimit.v2;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
@Service
public class TokenBucketRateLimiter {

    private final ConcurrentHashMap <String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitDecision check(String apiKey){

        long now = System.currentTimeMillis();

        Bucket bucket = buckets.computeIfAbsent(apiKey,
                k->new Bucket(now, RateLimitConfig.CAPACITY));

        synchronized (bucket){
            long elapsedMs = now - bucket.lastRefillTime;
            long tokensToAdd = elapsedMs / RateLimitConfig.REFILL_INTERVAL_MS;

            if(tokensToAdd > 0){
                bucket.tokens = Math.min(
                        RateLimitConfig.CAPACITY,
                        bucket.tokens + tokensToAdd
                );

                bucket.lastRefillTime += tokensToAdd * RateLimitConfig.REFILL_INTERVAL_MS;
            }

            if(bucket.tokens > 0){
                bucket.tokens--;
                return RateLimitDecision.allowed(bucket.tokens, 0);
            }
            else{
                long msUntilNextToken = RateLimitConfig.REFILL_INTERVAL_MS - (now - bucket.lastRefillTime);

                long retryAfterSeconds = Math.max(1, (msUntilNextToken+999) / 1000);

                return RateLimitDecision.denied(0, retryAfterSeconds);
            }
        }

    }



}
