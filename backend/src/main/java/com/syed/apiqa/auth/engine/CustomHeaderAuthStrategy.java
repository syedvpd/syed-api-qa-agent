package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.time.OffsetDateTime;

@Component
public class CustomHeaderAuthStrategy implements AuthenticationStrategy {

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.CUSTOM_HEADER;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
        if (!profile.getCustomHeaders().isEmpty()) {
            for (var entry : profile.getCustomHeaders().entrySet()) {
                session.setAuthHeader(entry.getKey(), entry.getValue());
            }
            session.setState(AuthLifecycleState.AUTHENTICATED);
            session.setLastAuthenticatedAt(OffsetDateTime.now());
            return true;
        }
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("Missing custom headers in profile");
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session != null) {
            for (var entry : session.getAuthHeaders().entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
    }
}
