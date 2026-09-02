package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.time.OffsetDateTime;

@Component
public class BearerAuthStrategy implements AuthenticationStrategy {

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.BEARER_TOKEN;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
        String token = profile.getToken() != null ? profile.getToken() : profile.getSecretOrPassword();
        if (token != null && !token.isBlank()) {
            session.setAccessToken(token.trim());
            session.setTokenType("Bearer");
            session.setState(AuthLifecycleState.AUTHENTICATED);
            session.setLastAuthenticatedAt(OffsetDateTime.now());
            return true;
        }
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("Missing token in Bearer credential profile");
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session != null && session.getAccessToken() != null) {
            connection.setRequestProperty("Authorization", "Bearer " + session.getAccessToken());
        }
    }
}
