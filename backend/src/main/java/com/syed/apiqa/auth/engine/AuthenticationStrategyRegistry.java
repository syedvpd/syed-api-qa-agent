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
        return strategies.stream()
                .filter(s -> s.supports(CredentialProfile.AuthStrategy.BEARER_TOKEN))
                .findFirst()
                .orElseGet(() -> strategies.isEmpty() ? null : strategies.get(0));
    }
}
