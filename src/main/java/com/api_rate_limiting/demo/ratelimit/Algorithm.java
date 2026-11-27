package com.api_rate_limiting.demo.ratelimit;

public enum Algorithm {
    /** Smooth refill, allows bursts up to capacity. */
    TOKEN_BUCKET,
    /**
     * Weighted sliding window counter: about N requests per rolling window, in O(1) memory. It assumes
     * the previous window's requests were spread evenly, so bursts timed at a window boundary can get
     * up to 2N through within one rolling window. Use TOKEN_BUCKET when the limit must be strict.
     */
    SLIDING_WINDOW
}
