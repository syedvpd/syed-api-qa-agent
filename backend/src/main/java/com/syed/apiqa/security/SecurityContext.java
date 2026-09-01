package com.syed.apiqa.security;

/**
 * Thread-local context holding the cryptographically verified identity of the calling user.
 */
public final class SecurityContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private SecurityContext() {}

    public static void setCurrentUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT_USER.set(userId.trim());
        } else {
            CURRENT_USER.remove();
        }
    }

    public static String getCurrentUserId() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
