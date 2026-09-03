package com.syed.apiqa.auth.engine;

import com.syed.apiqa.auth.CredentialProfile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthenticationStrategyRegistry {

    private final List<AuthenticationStrategy> strategies;

    public AuthenticationStrategyRegistry(List<AuthenticationStrategy> strategies) {
        this.strategies = strategies != null ? strategies : List.of();
    }

    public AuthenticationStrategy getStrategy(CredentialProfile.AuthStrategy strategy) {
        if (strategy != null) {
            for (AuthenticationStrategy s : strategies) {
                if (s.supports(strategy)) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("AUTH_CONFIGURATION_REQUIRED: No authentication strategy handler registered for strategy [" + strategy + "]");
    }
}
