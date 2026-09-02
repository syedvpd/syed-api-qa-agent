package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

@Component
public class BasicAuthStrategy implements AuthenticationStrategy {

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.BASIC_AUTH;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
        String user = profile.getUsernameOrEmail() != null ? profile.getUsernameOrEmail() : "";
        String pass = profile.getSecretOrPassword() != null ? profile.getSecretOrPassword() : "";
        if (!user.isBlank() || !pass.isBlank()) {
            String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            session.setAuthHeader("Authorization", "Basic " + token);
            session.setState(AuthLifecycleState.AUTHENTICATED);
            session.setLastAuthenticatedAt(OffsetDateTime.now());
            return true;
        }
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("Missing username or password for Basic Auth");
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session != null && session.getAuthHeaders().containsKey("Authorization")) {
            connection.setRequestProperty("Authorization", session.getAuthHeaders().get("Authorization"));
        }
    }
}
