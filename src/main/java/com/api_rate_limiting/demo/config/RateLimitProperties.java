package com.api_rate_limiting.demo.config;

import com.api_rate_limiting.demo.ratelimit.Algorithm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything under {@code ratelimit.*} in application.yml. Validated at startup so a bad
 * config fails fast instead of failing on the first request.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        /* Paths that skip API-key auth and rate limiting. Everything else is protected (default deny). */
        @NotNull List<String> publicPaths,
        @NotBlank String apiKeyHeader,
        @NotNull FailMode failMode,
        @NotBlank String defaultPlan,
        @NotEmpty Map<String, @Valid PlanProperties> plans,
        @Valid @NotNull ApiKeyCache apiKeyCache,
        @Valid @NotNull CircuitBreaker circuitBreaker,
        @Valid @NotNull Security security) {

    /** What to do when Redis is unavailable. */
    public enum FailMode {
        /** Let requests through (availability over strictness). */
        OPEN,
        /** Reject requests with 503 (strictness over availability). */
        CLOSED
    }

    /**
     * @param capacity      token bucket: burst size; sliding window: requests per window
     * @param refillTokens  token bucket only: tokens added per {@code refillPeriod}
     * @param refillPeriod  token bucket only
     * @param window        sliding window only
     */
    public record PlanProperties(@NotNull Algorithm algorithm,
                                 @Positive long capacity,
                                 Long refillTokens,
                                 Duration refillPeriod,
                                 Duration window) {
    }

    public record ApiKeyCache(@NotNull Duration refreshAfter,
                              @NotNull Duration expireAfter,
                              @NotNull Duration negativeTtl,
                              @Positive long maxSize) {
    }

    public record CircuitBreaker(@Min(1) @Max(100) float failureRateThreshold,
                                 @Min(1) int slidingWindowSize,
                                 @Min(1) int minimumCalls,
                                 @NotNull Duration openDuration,
                                 @NotNull Duration slowCallThreshold) {
    }

    /**
     * @param adminToken   bearer token for /admin/**; blank disables the admin API
     * @param apiKeyPepper server-side secret mixed into API key hashes (HMAC-SHA256)
     */
    public record Security(String adminToken, String apiKeyPepper) {
    }
}
