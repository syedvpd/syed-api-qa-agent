package com.syed.apiqa.auth;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral Isolated Session for an Identity during a test run.
 * Manages identity-specific access tokens, refresh tokens, cookie jars, and custom auth headers.
 */
public class IdentitySession implements Serializable {

    private final String identityId;
    private final String identityName;
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private OffsetDateTime expiresAt;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> sessionVariables = new ConcurrentHashMap<>();

    public IdentitySession(String identityId, String identityName) {
        this.identityId = identityId;
        this.identityName = identityName;
    }

    public String getIdentityId() { return identityId; }
    public String getIdentityName() { return identityName; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType != null ? tokenType : "Bearer"; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
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
        List<String> sortedNames = new java.util.ArrayList<>(cookies.keySet());
        java.util.Collections.sort(sortedNames);
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
