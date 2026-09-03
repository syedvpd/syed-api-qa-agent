package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import java.util.List;

public class OperationSecurityDecision {

    public enum SecurityState {
        NO_SECURITY,
        AUTH_REQUIRED,
        AUTH_OPTIONAL,
        SECURITY_UNKNOWN,
        AUTH_BOOTSTRAP
    }

    private String operationId;
    private SecurityState securityState;
    private String requiredSchemes;
    private List<CredentialProfile> candidateIdentities;
    private CredentialProfile selectedIdentity;
    private boolean authenticationRequired;
    private boolean authenticationAvailable;
    private boolean authenticationDependency;
    private boolean authorizationCapability;
    private boolean executionAllowed;
    private String reason;
    private String confidence;

    public OperationSecurityDecision() {}

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public SecurityState getSecurityState() { return securityState; }
    public void setSecurityState(SecurityState securityState) { this.securityState = securityState; }

    public String getRequiredSchemes() { return requiredSchemes; }
    public void setRequiredSchemes(String requiredSchemes) { this.requiredSchemes = requiredSchemes; }

    public List<CredentialProfile> getCandidateIdentities() { return candidateIdentities; }
    public void setCandidateIdentities(List<CredentialProfile> candidateIdentities) { this.candidateIdentities = candidateIdentities; }

    public CredentialProfile getSelectedIdentity() { return selectedIdentity; }
    public void setSelectedIdentity(CredentialProfile selectedIdentity) { this.selectedIdentity = selectedIdentity; }

    public boolean isAuthenticationRequired() { return authenticationRequired; }
    public void setAuthenticationRequired(boolean authenticationRequired) { this.authenticationRequired = authenticationRequired; }

    public boolean isAuthenticationAvailable() { return authenticationAvailable; }
    public void setAuthenticationAvailable(boolean authenticationAvailable) { this.authenticationAvailable = authenticationAvailable; }

    public boolean isAuthenticationDependency() { return authenticationDependency; }
    public void setAuthenticationDependency(boolean authenticationDependency) { this.authenticationDependency = authenticationDependency; }

    public boolean isAuthorizationCapability() { return authorizationCapability; }
    public void setAuthorizationCapability(boolean authorizationCapability) { this.authorizationCapability = authorizationCapability; }

    public boolean isExecutionAllowed() { return executionAllowed; }
    public void setExecutionAllowed(boolean executionAllowed) { this.executionAllowed = executionAllowed; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
}
