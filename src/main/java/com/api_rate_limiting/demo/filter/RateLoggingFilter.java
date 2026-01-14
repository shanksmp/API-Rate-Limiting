package com.api_rate_limiting.demo.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;

@Component

public class RateLoggingFilter extends OncePerRequestFilter {

   private static final int LIMIT = 5;
        private static final long TIME_WINDOW_MS = 60000; // 1 minute

        private final ConcurrentHashMap<String, Bucket> requestTimes = new ConcurrentHashMap<>();

         private static class Bucket{
            volatile long windowStart;
            final AtomicInteger count = new AtomicInteger(0);

            Bucket(long start){
                this.windowStart = start;
            }
        }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String allowedKey = "test123";
        
        String apiKey = request.getHeader("X-API-Key");
        if(apiKey == null || apiKey.trim().isEmpty()/*path.startsWith("/api/")*/){            
            // System.out.println("Incoming Request:" + request.getMethod() + ' ' + path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Missing X-API-Key");
            return;
        }

        if(!apiKey.equals(allowedKey)){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Invalid X-API-Key");
            return;
        }
            long now = System.currentTimeMillis();
            Bucket bucket = requestTimes.computeIfAbsent(apiKey, k -> new Bucket(now));

            if (now - bucket.windowStart >= TIME_WINDOW_MS) {
                bucket.windowStart = now;
                bucket.count.set(0);
            }

            int currentCount = bucket.count.incrementAndGet();
            long resetInSeconds = (TIME_WINDOW_MS - (now - bucket.windowStart)) / 1000;

            if(currentCount > LIMIT){
                response.setStatus(429);
                response.setContentType("text/plain");
                response.setHeader("X-RateLimit-Limit", String.valueOf(LIMIT));
                response.setHeader("X-RateLimit-Remaining", "0");
                response.setHeader("X-RateLimit-Reset", String.valueOf(resetInSeconds));
                response.getWriter().write("Rate limit exceeded");
                return;
            }
            
            int remaining = Math.max(0, LIMIT - currentCount);
            response.setHeader("X-RateLimit-Limit", String.valueOf(LIMIT));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            response.setHeader("X-RateLimit-Reset", String.valueOf(resetInSeconds));

        filterChain.doFilter(request,response);
        
    }
}
