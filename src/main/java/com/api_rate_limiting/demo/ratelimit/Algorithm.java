package com.api_rate_limiting.demo.ratelimit;

public enum Algorithm {
    /** Smooth refill, allows bursts up to capacity. */
    TOKEN_BUCKET,
    /** Weighted sliding window counter: N requests per rolling window. */
    SLIDING_WINDOW
}
