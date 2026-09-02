package com.syed.apiqa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Production Security Filter enforcing trusted identity authentication for /api/** endpoints.
 * Completely eliminates spoofable client-supplied X-User-Id headers.
 */
@Component
@Order(1)
public class AuthSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenSecurityService tokenSecurityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${syed.security.test-mode:false}")
    private boolean testMode;

    @Value("${syed.security.auth-enabled:true}")
    private boolean authEnabled;

    public AuthSecurityFilter(TokenSecurityService tokenSecurityService) {
        this.tokenSecurityService = tokenSecurityService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (org.springframework.web.cors.CorsUtils.isPreFlightRequest(request) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // Public endpoints that do not require authentication
        return path.equals("/api/health") || path.equals("/health") || path.equals("/actuator/health") || path.equals("/") || path.startsWith("/api/auth/") || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        String tokenParam = request.getParameter("token");
        String xUserIdHeader = request.getHeader("X-User-Id");

        String rawToken = null;
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            rawToken = authHeader.substring(BEARER_PREFIX.length()).trim();
        } else if (tokenParam != null && !tokenParam.isBlank()) {
            rawToken = tokenParam.trim();
        }

        try {
            if (rawToken != null) {
                try {
                    String verifiedUserId = tokenSecurityService.validateToken(rawToken);
                    // Prevent forgery: client cannot supply a different X-User-Id than the verified token identity
                    if (xUserIdHeader != null && !xUserIdHeader.isBlank() && !xUserIdHeader.trim().equals(verifiedUserId)) {
                        log.warn("Identity forgery detected: X-User-Id [{}] does not match token identity [{}]",
                                xUserIdHeader, verifiedUserId);
                        sendError(response, HttpStatus.FORBIDDEN, "FORGED_IDENTITY",
                                "Supplied X-User-Id does not match verified cryptographic token identity");
                        return;
                    }
                    SecurityContext.setCurrentUserId(verifiedUserId);
                } catch (SecurityException se) {
                    log.warn("Invalid authentication token: {}", se.getMessage());
                    sendError(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", se.getMessage());
                    return;
                }
            } else if (testMode || !authEnabled) {
                // In explicit test mode only, allow legacy X-User-Id header for mock tests if no Bearer token was provided
                if (xUserIdHeader != null && !xUserIdHeader.isBlank()) {
                    SecurityContext.setCurrentUserId(xUserIdHeader.trim());
                }
            } else {
                // In production mode, requests without a valid Bearer token are rejected
                sendError(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                        "Missing or invalid Authorization Bearer token");
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
            SecurityContext.clear();
        }
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> errorPayload = Map.of(
                "error", code,
                "message", message,
                "timestamp", System.currentTimeMillis()
        );
        response.getWriter().write(objectMapper.writeValueAsString(errorPayload));
    }
}
