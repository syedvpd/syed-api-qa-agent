package com.syed.apiqa.api.dto;

import com.syed.apiqa.auth.CredentialProfile;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Strongly-typed request DTO for creating and launching autonomous API test runs.
 * Supports N dynamic credential profiles, multi-strategy authentication, environment selection,
 * timeout controls, and SSRF validation.
 */
public class CreateRunRequest implements Serializable {

    private String openapiUrl;
    private String environmentType;
    private String environment; // alias for environmentType
    private String authType = "NONE";
    private String authToken;
    private String authCredentials;
    private String authLoginUrl;
    private String authLoginPayload;
    private String authTokenPath;
    private String authRefreshUrl;
    private Integer timeoutSeconds = 600;
    private String safetyMode;
    private List<CredentialProfile> profiles = new ArrayList<>();

    public CreateRunRequest() {}

    public String getOpenapiUrl() { return openapiUrl; }
    public void setOpenapiUrl(String openapiUrl) { this.openapiUrl = openapiUrl; }

    public String getEnvironmentType() {
        return environmentType != null ? environmentType : environment;
    }
    public void setEnvironmentType(String environmentType) { this.environmentType = environmentType; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public String getAuthCredentials() {
        return authCredentials != null ? authCredentials : authToken;
    }
    public void setAuthCredentials(String authCredentials) { this.authCredentials = authCredentials; }

    public String getAuthLoginUrl() { return authLoginUrl; }
    public void setAuthLoginUrl(String authLoginUrl) { this.authLoginUrl = authLoginUrl; }

    public String getAuthLoginPayload() { return authLoginPayload; }
    public void setAuthLoginPayload(String authLoginPayload) { this.authLoginPayload = authLoginPayload; }

    public String getAuthTokenPath() { return authTokenPath; }
    public void setAuthTokenPath(String authTokenPath) { this.authTokenPath = authTokenPath; }

    public String getAuthRefreshUrl() { return authRefreshUrl; }
    public void setAuthRefreshUrl(String authRefreshUrl) { this.authRefreshUrl = authRefreshUrl; }

    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getSafetyMode() { return safetyMode; }
    public void setSafetyMode(String safetyMode) { this.safetyMode = safetyMode; }

    public List<CredentialProfile> getProfiles() { return profiles; }
    public void setProfiles(List<CredentialProfile> profiles) {
        this.profiles = profiles != null ? profiles : new ArrayList<>();
    }
}
