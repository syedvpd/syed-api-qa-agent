package com.syed.apiqa.auth;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic Credential Profile representing an arbitrary user or machine identity.
 * Supports N dynamic roles without hardcoding (0, 1, 5, 29, 100).
 */
public class CredentialProfile implements Serializable {

    public enum AuthStrategy {
        AUTO_DISCOVERED,
        LOGIN_ENDPOINT,
        BEARER_TOKEN,
        API_KEY,
        BASIC_AUTH,
        COOKIE,
        NO_AUTH
    }

    private String id;
    private String name; // e.g. "Admin", "Customer", "Manager", "ServiceAccount"
    private AuthStrategy strategy = AuthStrategy.AUTO_DISCOVERED;
    private String usernameOrEmail;
    private String secretOrPassword;
    private String token; // Optional pre-generated token or API key
    private String headerName; // For API_KEY or custom auth header
    private Map<String, String> customHeaders = new HashMap<>();
    private Map<String, String> customPayloadFields = new HashMap<>();

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

    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> customHeaders) { this.customHeaders = customHeaders; }

    public Map<String, String> getCustomPayloadFields() { return customPayloadFields; }
    public void setCustomPayloadFields(Map<String, String> customPayloadFields) { this.customPayloadFields = customPayloadFields; }
}
