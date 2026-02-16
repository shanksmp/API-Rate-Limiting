package com.api_rate_limiting.demo.ratelimit.v3;

public record RateLimitDecision(boolean allowed,
                                long remainingTokens,
                                long retryAfterSeconds) {

    public static RateLimitDecision allowed(long remainingTokens){
        return new RateLimitDecision(true, remainingTokens,0);
    }

    public static RateLimitDecision denied(long remainingTokens, long retryAfterSeconds){
        return new RateLimitDecision(false, remainingTokens, retryAfterSeconds);
    }
}
