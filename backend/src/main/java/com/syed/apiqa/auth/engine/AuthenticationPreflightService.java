package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Authentication Preflight Service.
 * Validates all configured credential profiles prior to launching full test executions,
 * determining whether sessions are established, tokens captured, or MFA is required.
 */
@Service
public class AuthenticationPreflightService {

    private final IdentitySessionManager sessionManager;

    public AuthenticationPreflightService(IdentitySessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public record PreflightReport(boolean allPassed, int totalIdentities, int authenticatedCount, int failedCount,
                                 Map<String, AuthLifecycleState> identityStates, List<String> errorDetails) {}

    public PreflightReport executePreflight(String testRunId, List<CredentialProfile> profiles, String targetBaseUrl) {
        if (profiles == null || profiles.isEmpty()) {
            return new PreflightReport(true, 0, 0, 0, Collections.emptyMap(), Collections.emptyList());
        }

        Map<String, AuthLifecycleState> states = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int authCount = 0;
        int failCount = 0;

        for (CredentialProfile profile : profiles) {
            if (profile.getStrategy() == CredentialProfile.AuthStrategy.NO_AUTH) {
                states.put(profile.getId(), AuthLifecycleState.AUTHENTICATED);
                authCount++;
                continue;
            }

            boolean success = sessionManager.authenticateIdentity(testRunId, profile, targetBaseUrl);
            IdentitySession session = sessionManager.getOrCreateSession(testRunId, profile);

            states.put(profile.getId(), session.getState());
            if (success && session.getState() == AuthLifecycleState.AUTHENTICATED) {
                authCount++;
            } else {
                failCount++;
                errors.add("Identity '" + profile.getName() + "' failed authentication: " + session.getLastErrorMessage());
            }
        }

        boolean allPassed = failCount == 0;
        return new PreflightReport(allPassed, profiles.size(), authCount, failCount, states, errors);
    }
}
