package com.syed.apiqa.contract.schema;

import java.io.Serializable;

/**
 * Bounded complexity safety budget for recursive schema graph resolution.
 * Prevents reference-bomb and out-of-memory denial-of-service vulnerabilities.
 */
public class SchemaComplexityBudget implements Serializable {

    private int maxExpansionDepth = 10;
    private int maxGeneratedProperties = 250;
    private int maxGeneratedArrayItems = 10;
    private int maxReferenceExpansionCount = 500;
    private long maxTraversalTimeMs = 2000L;

    public SchemaComplexityBudget() {}

    public SchemaComplexityBudget(int maxExpansionDepth, int maxGeneratedProperties, int maxGeneratedArrayItems) {
        this.maxExpansionDepth = maxExpansionDepth;
        this.maxGeneratedProperties = maxGeneratedProperties;
        this.maxGeneratedArrayItems = maxGeneratedArrayItems;
    }

    public int getMaxExpansionDepth() { return maxExpansionDepth; }
    public void setMaxExpansionDepth(int maxExpansionDepth) { this.maxExpansionDepth = maxExpansionDepth; }

    public int getMaxGeneratedProperties() { return maxGeneratedProperties; }
    public void setMaxGeneratedProperties(int maxGeneratedProperties) { this.maxGeneratedProperties = maxGeneratedProperties; }

    public int getMaxGeneratedArrayItems() { return maxGeneratedArrayItems; }
    public void setMaxGeneratedArrayItems(int maxGeneratedArrayItems) { this.maxGeneratedArrayItems = maxGeneratedArrayItems; }

    public int getMaxReferenceExpansionCount() { return maxReferenceExpansionCount; }
    public void setMaxReferenceExpansionCount(int maxReferenceExpansionCount) { this.maxReferenceExpansionCount = maxReferenceExpansionCount; }

    public long getMaxTraversalTimeMs() { return maxTraversalTimeMs; }
    public void setMaxTraversalTimeMs(long maxTraversalTimeMs) { this.maxTraversalTimeMs = maxTraversalTimeMs; }
}
