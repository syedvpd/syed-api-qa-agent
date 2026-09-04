package com.syed.apiqa.auth;

import com.syed.apiqa.auth.canonical.AuthLifecycleState;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ephemeral Isolated Session for an Identity during a test run.
 * Manages identity-specific access tokens, refresh tokens, cookie jars, session variables,
 * lifecycle state, and coordinated concurrency locking to prevent refresh storms.
 */
public class IdentitySession implements Serializable {

    private final String identityId;
    private final String identityName;
    private String testRunId;
    private String tenantId;
    private String authStrategy;
    private AuthLifecycleState state = AuthLifecycleState.CREATED;
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private OffsetDateTime expiresAt;
    private OffsetDateTime lastAuthenticatedAt;
    private String lastErrorMessage;

    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> sessionVariables = new ConcurrentHashMap<>();

    // Transitive concurrency lock for token refresh coordination
    private final transient ReentrantLock refreshLock = new ReentrantLock();

    public IdentitySession(String identityId, String identityName) {
        this.identityId = identityId;
        this.identityName = identityName;
    }

    public String getTestRunId() { return testRunId; }
    public void setTestRunId(String testRunId) { this.testRunId = testRunId; }

    public String getIdentityId() { return identityId; }
    public String getIdentityName() { return identityName; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAuthStrategy() { return authStrategy; }
    public void setAuthStrategy(String authStrategy) { this.authStrategy = authStrategy; }

    public AuthLifecycleState getState() { return state; }
    public void setState(AuthLifecycleState state) { this.state = state; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType != null ? tokenType : "Bearer"; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public OffsetDateTime getLastAuthenticatedAt() { return lastAuthenticatedAt; }
    public void setLastAuthenticatedAt(OffsetDateTime lastAuthenticatedAt) { this.lastAuthenticatedAt = lastAuthenticatedAt; }

    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }

    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isAuthenticated() {
        return state == AuthLifecycleState.AUTHENTICATED && (accessToken != null || !cookies.isEmpty() || !authHeaders.isEmpty());
    }

    public ReentrantLock getRefreshLock() {
        return refreshLock;
    }

    public void addCookie(String name, String value) {
        if (name != null && value != null) {
            cookies.put(name.trim(), value.trim());
        }
    }

    public Map<String, String> getCookies() {
        return Collections.unmodifiableMap(cookies);
    }

    public String getCookieHeader() {
        if (cookies.isEmpty()) return null;
        List<String> sortedNames = new ArrayList<>(cookies.keySet());
        Collections.sort(sortedNames);
        StringBuilder sb = new StringBuilder();
        for (String name : sortedNames) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(name).append("=").append(cookies.get(name));
        }
        return sb.toString();
    }

    public void setAuthHeader(String name, String value) {
        if (name != null && value != null) {
            authHeaders.put(name.trim(), value.trim());
        }
    }

    public Map<String, String> getAuthHeaders() {
        return Collections.unmodifiableMap(authHeaders);
    }

    public void setSessionVariable(String name, String value) {
        if (name != null && value != null) {
            sessionVariables.put(name.trim(), value.trim());
        }
    }

    public String getSessionVariable(String name) {
        return sessionVariables.get(name);
    }

    public Map<String, String> getAllSessionVariables() {
        return Collections.unmodifiableMap(sessionVariables);
    }
}
