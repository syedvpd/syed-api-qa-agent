package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.time.OffsetDateTime;

@Component
public class CookieSessionStrategy implements AuthenticationStrategy {

    @Override
    public boolean supports(CredentialProfile.AuthStrategy strategy) {
        return strategy == CredentialProfile.AuthStrategy.COOKIE;
    }

    @Override
    public boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) {
        String cookieName = profile.getCookieName() != null ? profile.getCookieName() : "session_id";
        String cookieVal = profile.getToken() != null ? profile.getToken() : profile.getSecretOrPassword();

        if (cookieVal != null && !cookieVal.isBlank()) {
            session.addCookie(cookieName, cookieVal.trim());
            session.setState(AuthLifecycleState.AUTHENTICATED);
            session.setLastAuthenticatedAt(OffsetDateTime.now());
            return true;
        }
        session.setState(AuthLifecycleState.AUTH_FAILED);
        session.setLastErrorMessage("Missing cookie value in profile");
        return false;
    }

    @Override
    public void applyToRequest(IdentitySession session, CredentialProfile profile, HttpURLConnection connection) {
        if (session != null) {
            String cookieHeader = session.getCookieHeader();
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader);
            }
        }
    }
}
