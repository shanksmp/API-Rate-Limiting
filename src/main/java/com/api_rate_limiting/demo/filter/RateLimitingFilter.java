package com.api_rate_limiting.demo.filter;

import com.api_rate_limiting.demo.ratelimit.v2.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.v2.TokenBucketRateLimiter;
import com.api_rate_limiting.demo.security.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component

public class RateLimitingFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitingFilter(ApiKeyService apiKeyService, TokenBucketRateLimiter rateLimiter) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader("X-API-KEY");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Missing API Key");
            return;
        }

        if (!apiKeyService.isValid(apiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Invalid API Key");
            return;
        }

        RateLimitDecision decision = rateLimiter.check(apiKey);

        response.setHeader("X-Rate-Limiting-Remaining", String.valueOf(decision.getRemaining()));

        if (!decision.isAllowed()) {
            response.setStatus(429);
            response.setContentType("text/plain");

            response.setHeader("Retry-After", String.valueOf(decision.getRetryAfterSeconds()));

            response.getWriter().write("Too Many Requests");
            return;
        }


        filterChain.doFilter(request, response);


    }
}
