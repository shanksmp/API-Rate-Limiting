package com.api_rate_limiting.demo.ratelimit.v2;

public class RateLimitDecision {

    private final boolean allowed;
    private final long remaining;
    private final long retryAfterSeconds;

    private RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds){
        this.allowed = allowed;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RateLimitDecision allowed(long remaining, long retryAfterSeconds){
        return new RateLimitDecision(true, remaining, retryAfterSeconds);
    }
    public static RateLimitDecision denied(long remaining, long retryAfterSeconds){
        return new RateLimitDecision(false, remaining, retryAfterSeconds);
    }

    public boolean isAllowed(){
        return allowed;
    }

    public long getRemaining(){
        return remaining;
    }

    public long getRetryAfterSeconds(){
        return retryAfterSeconds;
    }
}
