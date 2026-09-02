package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;

/**
 * Pluggable Authentication Strategy interface.
 * Implements protocol-specific authentication and header/cookie resolution.
 */
public interface AuthenticationStrategy {

    boolean supports(CredentialProfile.AuthStrategy strategy);

    boolean authenticate(CredentialProfile profile, IdentitySession session, String targetBaseUrl) throws Exception;

    void applyToRequest(IdentitySession session, CredentialProfile profile, java.net.HttpURLConnection connection);
}
