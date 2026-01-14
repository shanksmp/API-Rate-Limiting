package com.api_rate_limiting.demo.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component

public class RateLoggingFilter extends OncePerRequestFilter {

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
        

        filterChain.doFilter(request,response);
        
    }
}
