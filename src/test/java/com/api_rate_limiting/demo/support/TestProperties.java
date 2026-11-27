package com.api_rate_limiting.demo.support;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.config.RateLimitProperties.FailMode;
import com.api_rate_limiting.demo.config.RateLimitProperties.PlanProperties;
import com.api_rate_limiting.demo.ratelimit.Algorithm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class TestProperties {

    public static final String PEPPER = "unit-test-pepper-0123456789abcdef0123";
    public static final String ADMIN_TOKEN = "unit-test-admin-token-0123456789abcdef";

    private TestProperties() {
    }

    public static RateLimitProperties defaults() {
        return withFailMode(FailMode.OPEN);
    }

    public static RateLimitProperties withFailMode(FailMode mode) {
        return build(mode, ADMIN_TOKEN, PEPPER);
    }

    public static RateLimitProperties withSecrets(String adminToken, String pepper) {
        return build(FailMode.OPEN, adminToken, pepper);
    }

    private static RateLimitProperties build(FailMode mode, String adminToken, String pepper) {
        return new RateLimitProperties(
                List.of("/public/**", "/admin/**", "/actuator/**", "/error"),
                "X-API-Key",
                mode,
                "free",
                Map.of("free", new PlanProperties(Algorithm.TOKEN_BUCKET, 10, 10L, Duration.ofMinutes(1), null)),
                new RateLimitProperties.ApiKeyCache(Duration.ofSeconds(30), Duration.ofMinutes(10),
                        Duration.ofSeconds(30), 1000),
                Duration.ofDays(30),
                new RateLimitProperties.AuthFailureThrottle(5, 1),
                new RateLimitProperties.CircuitBreaker(50, 50, 20, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                new RateLimitProperties.Security(adminToken, pepper));
    }
}
