package com.syed.apiqa.contract.schema;

import com.syed.apiqa.domain.ContractConfidence;
import com.syed.apiqa.domain.GenerationTrace;

import java.io.Serializable;
import java.util.List;

/**
 * Explicit, typed outcome of schema data synthesis.
 * Guarantees zero silent scalar fallbacks when an object schema fails.
 */
public sealed interface SchemaGenerationResult extends Serializable {

    record Success(Object value, ContractConfidence confidence, List<GenerationTrace> traces)
            implements SchemaGenerationResult {}

    record ComplexityLimitExceeded(String propertyPath, String budgetExceeded, int configuredLimit, String reason)
            implements SchemaGenerationResult {}

    record UnsupportedConstraint(String propertyPath, String constraintType, String details)
            implements SchemaGenerationResult {}

    record MissingRequiredReference(String refPointer, String reason)
            implements SchemaGenerationResult {}

    record GenerationFailure(String propertyPath, String reason, String details)
            implements SchemaGenerationResult {}
}
