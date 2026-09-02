package com.syed.apiqa.auth.discovery;

import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Authentication Discovery Service.
 * Analyzes normalized CanonicalApiModel operations using generic semantic signals
 * to identify candidate authentication/login endpoints and token response paths.
 */
@Service
public class AuthenticationDiscovery {

    public record AuthCandidate(String operationId, String path, String method, double confidenceScore, List<String> evidence) {}

    private static final List<String> AUTH_PATH_SIGNALS = List.of(
            "/login", "/auth/login", "/authenticate", "/oauth/token", "/token", "/session", "/api/auth/login", "/api/v1/auth/login"
    );

    public List<AuthCandidate> discoverLoginEndpoints(CanonicalApiModel model) {
        List<AuthCandidate> candidates = new ArrayList<>();
        if (model == null || model.getOperations() == null) return candidates;

        for (CanonicalApiModel.CanonicalOperation op : model.getOperations()) {
            if (!"POST".equalsIgnoreCase(op.getMethod())) continue;

            double score = 0.0;
            List<String> evidence = new ArrayList<>();
            String pathLower = op.getPath().toLowerCase();

            for (String sig : AUTH_PATH_SIGNALS) {
                if (pathLower.endsWith(sig) || pathLower.contains(sig)) {
                    score += 0.5;
                    evidence.add("Path contains authentication signal: " + sig);
                    break;
                }
            }

            if (op.getSummary() != null && op.getSummary().toLowerCase().contains("login")) {
                score += 0.3;
                evidence.add("Summary indicates login operation");
            }

            if (op.getOperationId() != null && (op.getOperationId().toLowerCase().contains("login") || op.getOperationId().toLowerCase().contains("auth"))) {
                score += 0.2;
                evidence.add("OperationId contains auth signal");
            }

            if (score > 0.3) {
                candidates.add(new AuthCandidate(op.getOperationId(), op.getPath(), op.getMethod(), Math.min(1.0, score), evidence));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.confidenceScore(), a.confidenceScore()));
        return candidates;
    }
}
