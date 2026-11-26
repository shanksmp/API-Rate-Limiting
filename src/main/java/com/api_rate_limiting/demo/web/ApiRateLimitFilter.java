package com.api_rate_limiting.demo.web;

import com.api_rate_limiting.demo.apikey.ApiKey;
import com.api_rate_limiting.demo.apikey.ApiKeyService;
import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.ratelimit.Plan;
import com.api_rate_limiting.demo.ratelimit.PlanRegistry;
import com.api_rate_limiting.demo.ratelimit.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.RateLimitService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates the API key and applies the key's rate limit plan.
 * <p>
 * Default deny: every path needs a valid key unless it matches {@code ratelimit.public-paths}.
 * Response headers follow the IETF RateLimit header draft.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    public static final String API_KEY_ATTRIBUTE = "ratelimit.apiKey";

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private final ApiKeyService apiKeys;
    private final PlanRegistry plans;
    private final RateLimitService rateLimits;
    private final ProblemWriter problems;
    private final String headerName;
    private final List<PathPattern> publicPaths;
    private final MeterRegistry meters;

    public ApiRateLimitFilter(ApiKeyService apiKeys, PlanRegistry plans, RateLimitService rateLimits,
                              ProblemWriter problems, RateLimitProperties props, MeterRegistry meters) {
        this.apiKeys = apiKeys;
        this.plans = plans;
        this.rateLimits = rateLimits;
        this.problems = problems;
        this.headerName = props.apiKeyHeader();
        this.publicPaths = props.publicPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.of(request);
        if (RequestPaths.hasDotSegments(path)) {
            problems.write(response, HttpStatus.BAD_REQUEST, "Invalid request path");
            return;
        }
        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String secret = request.getHeader(headerName);
        if (secret == null || secret.isBlank()) {
            reject(response, "missing", "Missing API key in the " + headerName + " header");
            return;
        }

        Optional<ApiKey> key;
        try {
            key = apiKeys.authenticate(secret.trim());
        } catch (RuntimeException e) {
            log.warn("API key lookup failed: {}", e.toString());
            response.setHeader("Retry-After", "1");
            problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable");
            return;
        }
        if (key.isEmpty()) {
            // Same answer for unknown, malformed and revoked keys: don't help anyone enumerate keys
            reject(response, "invalid", "Invalid API key");
            return;
        }

        Plan plan = plans.resolve(key.get().plan());
        RateLimitDecision decision = rateLimits.check(key.get().id(), plan, 1);

        if (!decision.degraded()) {
            response.setHeader("RateLimit-Limit", Long.toString(decision.limit()));
            response.setHeader("RateLimit-Remaining", Long.toString(decision.remaining()));
            response.setHeader("RateLimit-Reset", Long.toString(ceilSeconds(decision.resetMs())));
            response.setHeader("RateLimit-Policy", plan.capacity() + ";w=" + plan.policyWindowSeconds());
        }

        switch (decision.outcome()) {
            case ALLOWED, FAIL_OPEN -> {
                request.setAttribute(API_KEY_ATTRIBUTE, key.get());
                chain.doFilter(request, response);
            }
            case DENIED -> {
                response.setHeader("Retry-After", Long.toString(Math.max(1, ceilSeconds(decision.retryAfterMs()))));
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limit of plan '" + plan.name() + "' exceeded");
            }
            case FAIL_CLOSED -> {
                response.setHeader("Retry-After", "1");
                problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable");
            }
        }
    }

    private boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern p : publicPaths) {
            if (p.matches(container)) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response, String reason, String detail) throws IOException {
        Counter.builder("ratelimiter.auth.failures").tag("reason", reason).register(meters).increment();
        response.setHeader("WWW-Authenticate", "ApiKey header=\"" + headerName + "\"");
        problems.write(response, HttpStatus.UNAUTHORIZED, detail);
    }

    private static long ceilSeconds(long ms) {
        return (ms + 999) / 1000;
    }
}
