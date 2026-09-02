package com.syed.apiqa.contract.example;

import com.syed.apiqa.domain.ContractConfidence;

/**
 * Strict 11-level example priority ordering.
 * Enforces inspectable and deterministic priority evaluation across the contract.
 */
public enum ExamplePriority {
    USER_OVERRIDE(1, ContractConfidence.HIGH),
    OPERATION_EXAMPLE(2, ContractConfidence.HIGH),
    REQUEST_BODY_EXAMPLE(3, ContractConfidence.HIGH),
    MEDIA_TYPE_EXAMPLE(4, ContractConfidence.HIGH),
    PROPERTY_EXAMPLE(5, ContractConfidence.HIGH),
    SCHEMA_EXAMPLE(6, ContractConfidence.HIGH),
    DEFAULT(7, ContractConfidence.MEDIUM),
    ENUM(8, ContractConfidence.MEDIUM),
    CONSTRAINT_GENERATION(9, ContractConfidence.MEDIUM),
    SEMANTIC_FORMAT_GENERATION(10, ContractConfidence.MEDIUM),
    SAFE_DETERMINISTIC_FALLBACK(11, ContractConfidence.LOW);

    private final int level;
    private final ContractConfidence defaultConfidence;

    ExamplePriority(int level, ContractConfidence defaultConfidence) {
        this.level = level;
        this.defaultConfidence = defaultConfidence;
    }

    public int getLevel() { return level; }
    public ContractConfidence getDefaultConfidence() { return defaultConfidence; }
}
