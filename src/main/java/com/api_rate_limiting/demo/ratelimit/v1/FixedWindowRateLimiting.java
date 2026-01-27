package com.api_rate_limiting.demo.ratelimit.v1;

import java.util.concurrent.ConcurrentHashMap;
public class FixedWindowRateLimiting {

    private final ConcurrentHashMap <String, Bucket> requestTimes = new ConcurrentHashMap<>();

    public RateLimitDecision check (String apiKey){
        long now = System.currentTimeMillis();



        Bucket bucket = requestTimes.computeIfAbsent(apiKey,
                k-> new Bucket(now));

        synchronized (bucket){
            long elapsedTime = now - bucket.windowStart;

            if(elapsedTime >= RateLimitConfig.TIME_WINDOW_MS){
                bucket.windowStart = now;
                bucket.count.set(0);
            }



            if(bucket.count.get() < RateLimitConfig.LIMIT){
                int newCount = bucket.count.incrementAndGet();
                long remaining = RateLimitConfig.LIMIT - newCount;
                return RateLimitDecision.allowed(remaining,0);
            }

            long windowEnd = bucket.windowStart + RateLimitConfig.TIME_WINDOW_MS;
            long millisLeft = Math.max(0,windowEnd - now);
            long retryAfterSeconds = (millisLeft + 999)/1000;

            return RateLimitDecision.denied(0,retryAfterSeconds);
        }
    }

}
