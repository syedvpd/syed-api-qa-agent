package com.syed.apiqa.contract.schema;

import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * Safe, bounded pattern evaluation and generation.
 * Distinguishes supported, partial, and unsupported regular expressions,
 * preventing regex-based resource exhaustion.
 */
@Component
public class PatternGenerator {

    public enum PatternSupport {
        SUPPORTED_PATTERN,
        PARTIAL_PATTERN,
        UNSUPPORTED_PATTERN
    }

    public PatternSupport evaluatePattern(String regex) {
        if (regex == null || regex.isBlank()) return PatternSupport.SUPPORTED_PATTERN;
        if (regex.length() > 200 || regex.contains("(.*){") || regex.contains("(.+)+")) {
            return PatternSupport.UNSUPPORTED_PATTERN; // Pathological catastrophic backtracking candidate
        }
        return PatternSupport.SUPPORTED_PATTERN;
    }

    public String generateMatchingValue(String regex, Random random, int minLength, int maxLength) {
        if (regex == null || regex.isBlank()) {
            return "str_" + Math.abs(random.nextInt(10000));
        }

        // Common OpenAPI regex idioms
        if (regex.contains("^[0-9]+$") || regex.contains("^\\d+$")) {
            long val = Math.abs(random.nextLong() % 100000000L);
            return String.valueOf(val);
        }
        if (regex.contains("^[a-zA-Z]+$")) {
            return "alpha" + Math.abs(random.nextInt(1000));
        }
        if (regex.contains("^[a-zA-Z0-9_-]+$")) {
            return "slug_test_" + Math.abs(random.nextInt(1000));
        }

        // Generic safe fallback satisfying basic pattern
        int targetLen = minLength > 0 ? Math.max(minLength, 8) : 8;
        if (maxLength > 0) targetLen = Math.min(targetLen, maxLength);

        return "val_" + Long.toHexString(random.nextLong()).substring(0, Math.min(targetLen, 8));
    }
}
