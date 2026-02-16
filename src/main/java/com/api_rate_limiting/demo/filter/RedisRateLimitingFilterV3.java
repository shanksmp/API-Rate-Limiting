package com.api_rate_limiting.demo.filter;

import com.api_rate_limiting.demo.ratelimit.v3.RateLimitDecision;
import com.api_rate_limiting.demo.ratelimit.v3.RedisTokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)

public class RedisRateLimitingFilterV3 extends OncePerRequestFilter {

    private final RedisTokenBucketRateLimiter limiter;

    public RedisRateLimitingFilterV3(RedisTokenBucketRateLimiter limiter){
        this.limiter = limiter;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-KEY");

        if(apiKey == null || apiKey.isBlank()){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing Api Key");
            return;

        }

        RateLimitDecision decision = limiter.check(apiKey);

        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));

        if(!decision.allowed()){
            response.setStatus(429);
            response.setHeader("Retry After", String.valueOf(decision.retryAfterSeconds()));
            response.getWriter().write("Rate Limit Exceeded");
            return;

        }

        filterChain.doFilter(request, response);
    }
}
