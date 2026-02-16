package com.api_rate_limiting.demo.ratelimit.v3;

public class RateLimitConfig {
    //Capacity of the bucket (max burst)
    public static final long CAPACITY = 5;

    //Refill one token every 12 seconds (Token Bucket Algorithm)
    public static final long REFILL_INTERVAL_MS = 12_000;

    // Redis key TTL to prevent unbound growth (1 hour)
    public static final long REDIS_TTL_SECONDS =3600;

    private RateLimitConfig(){};
}
