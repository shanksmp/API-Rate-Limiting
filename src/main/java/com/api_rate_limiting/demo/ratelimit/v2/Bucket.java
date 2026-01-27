package com.api_rate_limiting.demo.ratelimit.v2;

public class Bucket {

    long tokens;
    long lastRefillTime;

    public Bucket(long now, long capacity){
        this.tokens = capacity;
        this.lastRefillTime = now;
    }
}
