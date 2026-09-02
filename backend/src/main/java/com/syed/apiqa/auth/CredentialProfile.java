package com.syed.apiqa.auth;

import java.io.Serializable;
import java.util.*;

/**
 * Dynamic Credential Profile representing an arbitrary user or machine identity.
 * Supports N dynamic identities without hardcoding (0, 1, 5, 29, 100+).
 */
public class CredentialProfile implements Serializable {

    public enum AuthStrategy {
        AUTO_DISCOVERED,
        LOGIN_ENDPOINT,
        BEARER_TOKEN,
        API_KEY,
        BASIC_AUTH,
        COOKIE,
        OAUTH2_CLIENT_CREDENTIALS,
        CUSTOM_HEADER,
        NO_AUTH
    }

    private String id;
    private String name; // e.g. "Admin", "Customer", "Manager", "ServiceAccount"
    private AuthStrategy strategy = AuthStrategy.AUTO_DISCOVERED;
    private String usernameOrEmail;
    private String secretOrPassword;
    private String token; // Optional pre-generated token or API key
    private String headerName; // For API_KEY or custom auth header (e.g. "X-API-Key")
    private String queryParamName; // For query-based API keys
    private String cookieName; // For cookie-based authentication
    private String tenantId; // Optional multi-tenancy context
    private String environment; // e.g. "STAGING", "PRODUCTION"
    private List<String> scopes = new ArrayList<>();
    private Map<String, String> customHeaders = new LinkedHashMap<>();
    private Map<String, String> customPayloadFields = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public CredentialProfile() {}

    public CredentialProfile(String id, String name, AuthStrategy strategy, String usernameOrEmail, String secretOrPassword) {
        this.id = id;
        this.name = name;
        this.strategy = strategy;
        this.usernameOrEmail = usernameOrEmail;
        this.secretOrPassword = secretOrPassword;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AuthStrategy getStrategy() { return strategy; }
    public void setStrategy(AuthStrategy strategy) { this.strategy = strategy; }

    public String getUsernameOrEmail() { return usernameOrEmail; }
    public void setUsernameOrEmail(String usernameOrEmail) { this.usernameOrEmail = usernameOrEmail; }

    public String getSecretOrPassword() { return secretOrPassword; }
    public void setSecretOrPassword(String secretOrPassword) { this.secretOrPassword = secretOrPassword; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }

    public String getQueryParamName() { return queryParamName; }
    public void setQueryParamName(String queryParamName) { this.queryParamName = queryParamName; }

    public String getCookieName() { return cookieName; }
    public void setCookieName(String cookieName) { this.cookieName = cookieName; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes != null ? scopes : new ArrayList<>(); }

    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> customHeaders) { this.customHeaders = customHeaders != null ? customHeaders : new LinkedHashMap<>(); }

    public Map<String, String> getCustomPayloadFields() { return customPayloadFields; }
    public void setCustomPayloadFields(Map<String, String> customPayloadFields) { this.customPayloadFields = customPayloadFields != null ? customPayloadFields : new LinkedHashMap<>(); }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); }
}
