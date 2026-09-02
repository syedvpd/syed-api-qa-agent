package com.syed.apiqa.auth.matrix;

import com.syed.apiqa.auth.CredentialProfile;
import com.syed.apiqa.auth.IdentitySession;
import com.syed.apiqa.auth.canonical.AuthLifecycleState;
import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Authorization Matrix Engine.
 * Builds the Identity × Operation matrix, mapping identities to operations
 * with expected authorization outcomes (ALLOWED, DENIED_EXPECTED, NOT_APPLICABLE, BLOCKED_BY_AUTHENTICATION).
 */
@Service
public class AuthorizationMatrixEngine {

    public enum MatrixOutcome {
        ALLOWED,                     // Identity is expected to succeed (2xx)
        DENIED_EXPECTED,             // Identity is expected to be denied (403/401)
        PASS_EXPECTED_DENIAL,        // Test executed and successfully received expected 403
        BLOCKED_BY_AUTHENTICATION,   // Identity failed authentication; all dependent requests blocked
        NOT_APPLICABLE               // Operation is public / unauthenticated
    }

    public record MatrixCell(String identityId, String operationId, MatrixOutcome expectedOutcome, String reason) {}

    public List<MatrixCell> buildMatrix(CanonicalApiModel model,
                                        List<CredentialProfile> profiles,
                                        Map<String, IdentitySession> activeSessions) {
        List<MatrixCell> cells = new ArrayList<>();
        if (model == null || profiles == null) return cells;

        for (CanonicalApiModel.CanonicalOperation op : model.getOperations()) {
            boolean isPublic = op.getSecurityRequirements().isEmpty() && "GET".equalsIgnoreCase(op.getMethod());

            for (CredentialProfile profile : profiles) {
                IdentitySession session = activeSessions != null ? activeSessions.get(profile.getId()) : null;

                if (isPublic) {
                    cells.add(new MatrixCell(profile.getId(), op.getOperationId(), MatrixOutcome.NOT_APPLICABLE, "Operation requires no authentication"));
                } else if (session != null && session.getState() == AuthLifecycleState.AUTH_FAILED) {
                    cells.add(new MatrixCell(profile.getId(), op.getOperationId(), MatrixOutcome.BLOCKED_BY_AUTHENTICATION, "Identity authentication failed"));
                } else {
                    // Check if operation is admin/privileged vs standard user
                    boolean isPrivilegedOp = op.getPath().contains("admin") || (op.getSummary() != null && op.getSummary().toLowerCase().contains("admin"));
                    boolean isPrivilegedIdentity = profile.getName().toLowerCase().contains("admin") || profile.getScopes().contains("admin");

                    if (isPrivilegedOp && !isPrivilegedIdentity) {
                        cells.add(new MatrixCell(profile.getId(), op.getOperationId(), MatrixOutcome.DENIED_EXPECTED, "Unprivileged identity calling privileged operation"));
                    } else {
                        cells.add(new MatrixCell(profile.getId(), op.getOperationId(), MatrixOutcome.ALLOWED, "Identity holds required authorization"));
                    }
                }
            }
        }

        return cells;
    }
}
