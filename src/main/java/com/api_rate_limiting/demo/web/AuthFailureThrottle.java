package com.api_rate_limiting.demo.web;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Token bucket per client address for <em>failed</em> authentication attempts, held in this
 * instance's memory. Each failure costs a token; a client with no tokens left is rejected before
 * its key is looked up, so a stream of random fake keys can't turn into a stream of Redis lookups.
 * <p>
 * It's per instance, so the cluster-wide budget is this times the instance count. It doesn't stop a
 * botnet spread over many addresses; that needs limits at the edge (WAF / load balancer).
 */
@Component
public class AuthFailureThrottle {

    private final long capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private final Cache<String, Bucket> buckets;

    @Autowired
    public AuthFailureThrottle(RateLimitProperties props) {
        this(props.authFailureThrottle(), System::nanoTime);
    }

    AuthFailureThrottle(RateLimitProperties.AuthFailureThrottle config, LongSupplier nanoClock) {
        this.capacity = config.capacity();
        this.tokensPerNano = config.refillPerSecond() / 1e9;
        this.nanoClock = nanoClock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    /** @return true if this client has used up its failure budget */
    public boolean isBlocked(String client) {
        Bucket b = buckets.getIfPresent(client);
        if (b == null) {
            return false;
        }
        synchronized (b) {
            refill(b);
            return b.tokens < 1;
        }
    }

    public void recordFailure(String client) {
        Bucket b = buckets.get(client, k -> new Bucket(capacity, nanoClock.getAsLong()));
        synchronized (b) {
            refill(b);
            b.tokens = Math.max(0, b.tokens - 1);
        }
    }

    /** Seconds until a blocked client may try again. */
    public long retryAfterSeconds(String client) {
        Bucket b = buckets.getIfPresent(client);
        if (b == null) {
            return 0;
        }
        synchronized (b) {
            refill(b);
            double missing = Math.max(0, 1 - b.tokens);
            return Math.max(1, (long) Math.ceil(missing / tokensPerNano / 1e9));
        }
    }

    private void refill(Bucket b) {
        long now = nanoClock.getAsLong();
        b.tokens = Math.min(capacity, b.tokens + (now - b.lastNanos) * tokensPerNano);
        b.lastNanos = now;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }
}
