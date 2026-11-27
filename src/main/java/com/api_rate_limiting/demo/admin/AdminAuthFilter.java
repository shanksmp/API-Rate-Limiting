package com.api_rate_limiting.demo.admin;

import com.api_rate_limiting.demo.config.RateLimitProperties;
import com.api_rate_limiting.demo.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bearer-token auth for /admin/**. The admin API is disabled (404) when no token is configured.
 * Tokens are compared as SHA-256 digests with a constant-time check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
    static final int MIN_TOKEN_LENGTH = 32;
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final byte[] tokenDigest;
    private final ProblemWriter problems;

    public AdminAuthFilter(RateLimitProperties props, ProblemWriter problems) {
        this.problems = problems;
        String token = props.security().adminToken();
        if (token == null || token.isBlank()) {
            log.warn("ratelimit.security.admin-token is not set: the admin API is disabled");
            this.tokenDigest = null;
        } else if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("ratelimit.security.admin-token (env ADMIN_TOKEN) must be at least "
                    + MIN_TOKEN_LENGTH + " characters");
        } else {
            this.tokenDigest = sha256(token);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return !(path.equals("/admin") || path.startsWith("/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (tokenDigest == null) {
            problems.write(response, HttpStatus.NOT_FOUND, "Not found");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")
                || !MessageDigest.isEqual(tokenDigest, sha256(header.substring(7).trim()))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            problems.write(response, HttpStatus.UNAUTHORIZED, "Missing or invalid admin token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
