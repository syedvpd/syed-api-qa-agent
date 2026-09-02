package com.syed.apiqa.contract.validation;

import java.io.Serializable;

/**
 * Structured schema validation finding comparing actual response payload with contract expectation.
 */
public class SchemaValidationFinding implements Serializable {

    public enum Severity {
        CRITICAL,
        ERROR,
        WARNING,
        INFO
    }

    private final String jsonPath;
    private final String expectedRule;
    private final String actualValue;
    private final String violationType;
    private final Severity severity;

    public SchemaValidationFinding(String jsonPath, String expectedRule, String actualValue,
                                   String violationType, Severity severity) {
        this.jsonPath = jsonPath;
        this.expectedRule = expectedRule;
        this.actualValue = actualValue;
        this.violationType = violationType;
        this.severity = severity;
    }

    public String getJsonPath() { return jsonPath; }
    public String getExpectedRule() { return expectedRule; }
    public String getActualValue() { return actualValue; }
    public String getViolationType() { return violationType; }
    public Severity getSeverity() { return severity; }

    @Override
    public String toString() {
        return "[" + severity + "] " + jsonPath + ": " + violationType + " (Expected: " + expectedRule + ", Actual: " + actualValue + ")";
    }
}
