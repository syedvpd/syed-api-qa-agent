package com.syed.apiqa.domain.canonical;

import java.io.Serializable;

/**
 * Truthful Contract Capability Model.
 * Explicitly declares support status for protocols, formats, and schema features without silent approximations.
 */
public class ContractCapability implements Serializable {

    public enum SupportLevel {
        SUPPORTED,
        PARTIAL,
        UNSUPPORTED,
        BLOCKED_BY_SAFETY,
        INVALID_CONTRACT
    }

    private final String featureName;
    private final SupportLevel supportLevel;
    private final String explanation;

    public ContractCapability(String featureName, SupportLevel supportLevel, String explanation) {
        this.featureName = featureName;
        this.supportLevel = supportLevel;
        this.explanation = explanation;
    }

    public String getFeatureName() { return featureName; }
    public SupportLevel getSupportLevel() { return supportLevel; }
    public String getExplanation() { return explanation; }
}
