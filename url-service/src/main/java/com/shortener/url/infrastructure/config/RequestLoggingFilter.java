package com.shortener.url.infrastructure.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that:
 *  1. Attaches a unique requestId to MDC for structured log correlation
 *  2. Propagates requestId in response header (for client-side debugging)
 *  3. Adds production security headers on every response
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq = (HttpServletRequest)  req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        // Honour upstream request-id (from API gateway or caller), else generate
        String requestId = httpReq.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // Populate MDC so every log line from this thread carries the request ID
        MDC.put("requestId", requestId);
        MDC.put("method",    httpReq.getMethod());
        MDC.put("path",      httpReq.getRequestURI());

        // Echo the request ID back to callers
        httpRes.setHeader("X-Request-Id", requestId);

        // ── Security Headers ────────────────────────────────────────────────
        httpRes.setHeader("X-Content-Type-Options", "nosniff");
        httpRes.setHeader("X-Frame-Options",         "DENY");
        httpRes.setHeader("X-XSS-Protection",        "1; mode=block");
        httpRes.setHeader("Referrer-Policy",          "strict-origin-when-cross-origin");
        httpRes.setHeader("Strict-Transport-Security",
            "max-age=31536000; includeSubDomains; preload");
        httpRes.setHeader("Permissions-Policy",
            "geolocation=(), microphone=(), camera=()");

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.debug("Completed {} {} → {} in {}ms",
                httpReq.getMethod(), httpReq.getRequestURI(),
                httpRes.getStatus(), duration);
            MDC.clear();
        }
    }
}
