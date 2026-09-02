package com.syed.apiqa.auth.canonical;

import java.io.Serializable;

/**
 * Lifecycle state of an Identity Session during test run execution.
 */
public enum AuthLifecycleState implements Serializable {
    CREATED,
    AUTHENTICATING,
    AUTHENTICATED,
    EXPIRED,
    REFRESHING,
    AUTH_FAILED,
    MFA_REQUIRED,
    LOGGED_OUT
}
