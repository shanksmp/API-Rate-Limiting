package com.api_rate_limiting.demo.ratelimit.v1;

import java.util.concurrent.atomic.AtomicInteger;

public class Bucket {

    AtomicInteger count = new AtomicInteger(0);
    volatile long windowStart;

    public Bucket(long start){
        this.windowStart = start;
    }


}
