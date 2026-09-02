package com.syed.apiqa.domain;

import java.io.Serializable;

/**
 * Audit trace for a generated field or payload value.
 * Records the exact strategy, property path, and confidence used to synthesize data.
 */
public class GenerationTrace implements Serializable {

    private final String propertyPath;
    private final String strategyName;
    private final String reason;
    private final ContractConfidence confidence;

    public GenerationTrace(String propertyPath, String strategyName, String reason, ContractConfidence confidence) {
        this.propertyPath = propertyPath;
        this.strategyName = strategyName;
        this.reason = reason;
        this.confidence = confidence;
    }

    public String getPropertyPath() { return propertyPath; }
    public String getStrategyName() { return strategyName; }
    public String getReason() { return reason; }
    public ContractConfidence getConfidence() { return confidence; }

    @Override
    public String toString() {
        return "[" + confidence + "] " + propertyPath + " via " + strategyName + " (" + reason + ")";
    }
}
