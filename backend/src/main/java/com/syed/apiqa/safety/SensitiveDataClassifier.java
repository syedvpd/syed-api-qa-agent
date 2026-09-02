package com.syed.apiqa.safety;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Universal Sensitive Data Classifier & Redaction Engine.
 * Dynamically detects sensitive fields using generic semantic patterns,
 * generates safe deterministic dummy credentials, and provides uniform masking.
 */
@Service
public class SensitiveDataClassifier {

    public enum SensitivityType {
        PASSWORD,
        TOKEN,
        API_KEY,
        SECRET,
        CREDENTIAL,
        PERSONAL_DATA,
        AUTH_HEADER,
        SESSION_DATA,
        NON_SENSITIVE
    }

    private static final Set<String> PASSWORD_SIGNALS = Set.of(
            "password", "passwd", "pwd", "passphrase", "secret_key", "client_secret"
    );

    private static final Set<String> TOKEN_SIGNALS = Set.of(
            "token", "access_token", "refresh_token", "jwt", "auth_token", "id_token", "bearertoken", "session_token"
    );

    private static final Set<String> API_KEY_SIGNALS = Set.of(
            "api_key", "apikey", "x-api-key", "api-key", "secret_token", "app_key"
    );

    private static final Set<String> PERSONAL_SIGNALS = Set.of(
            "ssn", "social_security", "credit_card", "card_number", "cvv", "cvc", "national_id"
    );

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api_?key|auth|bearer|credential|private_?key|client_?secret).*"
    );

    public boolean isSensitive(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return false;
        return classify(fieldName) != SensitivityType.NON_SENSITIVE;
    }

    public SensitivityType classify(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return SensitivityType.NON_SENSITIVE;
        }

        String normalized = fieldName.trim().toLowerCase(Locale.ROOT).replace("-", "_");

        for (String sig : PASSWORD_SIGNALS) {
            if (normalized.contains(sig)) return SensitivityType.PASSWORD;
        }
        for (String sig : TOKEN_SIGNALS) {
            if (normalized.contains(sig)) return SensitivityType.TOKEN;
        }
        for (String sig : API_KEY_SIGNALS) {
            if (normalized.contains(sig)) return SensitivityType.API_KEY;
        }
        for (String sig : PERSONAL_SIGNALS) {
            if (normalized.contains(sig)) return SensitivityType.PERSONAL_DATA;
        }

        if (SENSITIVE_PATTERN.matcher(normalized).matches()) {
            return SensitivityType.SECRET;
        }

        return SensitivityType.NON_SENSITIVE;
    }

    public String redact(String value) {
        if (value == null || value.isBlank()) return value;
        return "[REDACTED_SECRET]";
    }

    public String generateSafeDummy(String fieldName, Random random) {
        SensitivityType type = classify(fieldName);
        int salt = Math.abs(random.nextInt(9000)) + 1000;

        return switch (type) {
            case PASSWORD -> "SafePassw0rd!" + salt;
            case TOKEN -> "sec_token_mock_" + Long.toHexString(random.nextLong());
            case API_KEY -> "ak_live_test_" + Long.toHexString(random.nextLong());
            case PERSONAL_DATA -> "999-00-" + salt;
            case SECRET, CREDENTIAL -> "sec_secret_" + Long.toHexString(random.nextLong());
            default -> "safe_val_" + salt;
        };
    }
}
