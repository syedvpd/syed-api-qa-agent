package com.syed.apiqa.auth.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.safety.PinnedConnectionManager;
import com.syed.apiqa.safety.SsrfProtectionGuard;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

@Component
public class OAuth2ClientCredentialsStrategy implements AuthenticationStrategy {

    private final SsrfProtectionGuard ssrfGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OAuth2ClientCredentialsStrategy(SsrfProtectionGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.OAUTH2_CLIENT_CREDENTIALS;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) throws Exception {
        String tokenUrl = profile.getMetadata().containsKey("tokenUrl") ?
                String.valueOf(profile.getMetadata().get("tokenUrl")) : targetBaseUrl + "/oauth/token";

        String clientId = profile.getUsernameOrEmail() != null ? profile.getUsernameOrEmail() : "";
        String clientSecret = profile.getSecretOrPassword() != null ? profile.getSecretOrPassword() : "";

        SsrfProtectionGuard.ValidatedTarget target = ssrfGuard.resolveAndValidate(tokenUrl);
        HttpURLConnection conn = PinnedConnectionManager.openPinnedConnection(target, 15);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        StringBuilder payload = new StringBuilder();
        payload.append("grant_type=client_credentials");
        payload.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        payload.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));

        if (!profile.getScopes().isEmpty()) {
            payload.append("&scope=").append(URLEncoder.encode(String.join(" ", profile.getScopes()), StandardCharsets.UTF_8));
        }

        try (var os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        var in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "";

        if (code >= 200 && code < 300) {
            JsonNode root = objectMapper.readTree(resp);
            String token = root.has("access_token") ? root.get("access_token").asText() :
                    (root.has("token") ? root.get("token").asText() : null);

            if (token != null && !token.isBlank()) {
                session.setAccessToken(token.trim());
                if (root.has("refresh_token")) session.setRefreshToken(root.get("refresh_token").asText());
                if (root.has("token_type")) session.setTokenType(root.get("token_type").asText());
                if (root.has("expires_in")) {
                    session.setExpiresAt(OffsetDateTime.now().plusSeconds(root.get("expires_in").asLong()));
                }
                session.setState(AuthLifecycleState.AUTHENTICATED);
                session.setLastAuthenticatedAt(OffsetDateTime.now());
                return true;
            }
        }

        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("OAuth2 token exchange failed with status " + code);
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session != null && session.getAccessToken() != null) {
            connection.setRequestProperty("Authorization", session.getTokenType() + " " + session.getAccessToken());
        }
    }
}
