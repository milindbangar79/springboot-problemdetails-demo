package com.spring.problemdetails.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rejects excess concurrent requests with HTTP 503 + Retry-After header.
 * Article lesson: clean 503 under overload prevents cascading failure across the cluster.
 */
@Component
public class LoadSheddingFilter implements Filter {

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final int maxConcurrentRequests;

    public LoadSheddingFilter(
            @Value("${app.max-concurrent-requests:5000}") int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        int current = activeRequests.incrementAndGet();
        try {
            if (current > maxConcurrentRequests) {
                activeRequests.decrementAndGet();
                HttpServletResponse http = (HttpServletResponse) response;
                http.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                http.setHeader("Retry-After", "1");
                http.setContentType("application/json");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
