package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Universal Identity Session Manager.
 * Maintains isolated sessions per testRunId and identityId.
 * Coordinates token refresh using per-session concurrency locks to completely eliminate refresh storms.
 */
@Service
public class IdentitySessionManager {

    private final AuthenticationStrategyRegistry strategyRegistry;
    private final Map<String, Map<String, IdentitySession>> runSessions = new ConcurrentHashMap<>();

    public IdentitySessionManager(AuthenticationStrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    public IdentitySession getOrCreateSession(String testRunId, CredentialProfile profile) {
        if (testRunId == null || profile == null) {
            return new IdentitySession("anonymous", "Anonymous");
        }

        Map<String, IdentitySession> sessions = runSessions.computeIfAbsent(testRunId, k -> new ConcurrentHashMap<>());
        return sessions.computeIfAbsent(profile.getId(), id -> {
            IdentitySession session = new IdentitySession(profile.getId(), profile.getName());
            session.setTenantId(profile.getTenantId());
            session.setAuthStrategy(profile.getStrategy().name());
            return session;
        });
    }

    public boolean authenticateIdentity(String testRunId, CredentialProfile profile, String targetBaseUrl) {
        IdentitySession session = getOrCreateSession(testRunId, profile);
        AuthenticationStrategy strategy = strategyRegistry.getStrategy(profile.getStrategy());

        try {
            session.setState(AuthLifecycleState.AUTHENTICATING);
            boolean success = strategy.authenticate(profile, session, targetBaseUrl);
            if (!success) {
                session.setState(AuthLifecycleState.AUTH_FAILED);
            }
            return success;
        } catch (Exception e) {
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage(e.getMessage());
            return false;
        }
    }

    /**
     * Coordinated token refresh preventing refresh storms across concurrent test steps.
     */
    public boolean refreshSessionCoordinated(String testRunId, CredentialProfile profile, String targetBaseUrl) {
        IdentitySession session = getOrCreateSession(testRunId, profile);
        ReentrantLock lock = session.getRefreshLock();

        lock.lock();
        try {
            // Double-checked locking: If another thread just completed refresh, return immediately
            if (!session.isExpired() && session.getState() == AuthLifecycleState.AUTHENTICATED) {
                return true;
            }

            session.setState(AuthLifecycleState.REFRESHING);
            AuthenticationStrategy strategy = strategyRegistry.getStrategy(profile.getStrategy());
            boolean success = strategy.authenticate(profile, session, targetBaseUrl);

            if (success) {
                session.setState(AuthLifecycleState.AUTHENTICATED);
            } else {
                session.setState(AuthLifecycleState.AUTH_FAILED);
            }
            return success;
        } catch (Exception e) {
            session.setState(AuthLifecycleState.AUTH_FAILED);
            session.setLastErrorMessage("Coordinated refresh failed: " + e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void clearRunSessions(String testRunId) {
        if (testRunId != null) {
            runSessions.remove(testRunId);
        }
    }
}
