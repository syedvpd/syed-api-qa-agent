package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.time.OffsetDateTime;

@Component
public class ApiKeyAuthStrategy implements AuthenticationStrategy {

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.API_KEY;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
        String key = profile.getToken() != null ? profile.getToken() : profile.getSecretOrPassword();
        if (key != null && !key.isBlank()) {
            String header = profile.getHeaderName() != null ? profile.getHeaderName() : "X-API-Key";
            session.setAuthHeader(header, key.trim());
            session.setState(AuthLifecycleState.AUTHENTICATED);
            session.setLastAuthenticatedAt(OffsetDateTime.now());
            return true;
        }
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("Missing API key value in profile");
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
