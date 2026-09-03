package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;

/**
 * Universal Auto-Discovered Authentication Strategy.
 * Inspects runtime credentials, contract metadata, and security schemes.
 * If authentication cannot be determined with certainty, refuses to guess
 * and marks session with AUTH_CONFIGURATION_REQUIRED.
 */
@Component
public class AutoDiscoveredAuthStrategy implements AuthenticationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AutoDiscoveredAuthStrategy.class);

    private final BearerAuthStrategy bearerStrategy;
    private final ApiKeyAuthStrategy apiKeyStrategy;
    private final BasicAuthStrategy basicStrategy;
    private final CookieSessionStrategy cookieStrategy;
    private final CustomHeaderAuthStrategy customHeaderStrategy;
    private final OAuth2ClientCredentialsStrategy oauth2Strategy;

    public AutoDiscoveredAuthStrategy(BearerAuthStrategy bearerStrategy,
                                     ApiKeyAuthStrategy apiKeyStrategy,
                                     BasicAuthStrategy basicStrategy,
                                     CookieSessionStrategy cookieStrategy,
                                     CustomHeaderAuthStrategy customHeaderStrategy,
                                     OAuth2ClientCredentialsStrategy oauth2Strategy) {
        this.bearerStrategy = bearerStrategy;
        this.apiKeyStrategy = apiKeyStrategy;
        this.basicStrategy = basicStrategy;
        this.cookieStrategy = cookieStrategy;
        this.customHeaderStrategy = customHeaderStrategy;
        this.oauth2Strategy = oauth2Strategy;
    }

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.AUTO_DISCOVERED;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) throws Exception {
        if (profile == null) {
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage("AUTH_CONFIGURATION_REQUIRED: No credential profile provided");
            return false;
        }

        // 1. Check for API Key (HeaderName provided)
        if (profile.getHeaderName() != null && !profile.getHeaderName().isBlank() &&
            profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved API Key Authentication for identity '{}'", profile.getName());
            return apiKeyStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 2. Check for Cookie Session
        if (profile.getCookieName() != null && !profile.getCookieName().isBlank() &&
            profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved Cookie Session Authentication for identity '{}'", profile.getName());
            return cookieStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 3. Check for Username / Password
        if (profile.getUsernameOrEmail() != null && !profile.getUsernameOrEmail().isBlank() &&
            profile.getSecretOrPassword() != null && !profile.getSecretOrPassword().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved HTTP Basic Authentication for identity '{}'", profile.getName());
            return basicStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 4. Check for Static Bearer Token
        if (profile.getToken() != null && !profile.getToken().isBlank()) {
            log.info("AUTO_DISCOVERED: Resolved Bearer Token Authentication for identity '{}'", profile.getName());
            return bearerStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 5. Check for Custom Header
        if (profile.getCustomHeaders() != null && !profile.getCustomHeaders().isEmpty()) {
            log.info("AUTO_DISCOVERED: Resolved Custom Header Authentication for identity '{}'", profile.getName());
            return customHeaderStrategy.authenticate(profile, session, targetBaseUrl);
        }

        // 6. Refuse to guess: Halt and require configuration
        log.warn("AUTO_DISCOVERED: Cannot determine authentication scheme for identity '{}' without explicit credentials or schemes", profile.getName());
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("AUTH_CONFIGURATION_REQUIRED: No recognizable credentials provided for auto-discovery");
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session == null || connection == null) return;
        session.getAuthHeaders().forEach(connection::setRequestProperty);
        if (!session.getCookies().isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            session.getCookies().forEach((k, v) -> {
                if (cookieHeader.length() > 0) cookieHeader.append("; ");
                cookieHeader.append(k).append("=").append(v);
            });
            connection.setRequestProperty("Cookie", cookieHeader.toString());
        }
    }
}
