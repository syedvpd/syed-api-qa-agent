package com.syed.apiqa.domain;

import java.io.Serializable;

public enum ContractConfidence {
    HIGH,    // Sourced directly from explicit OpenAPI example or user override
    MEDIUM,  // Sourced from schema constraints, enums, defaults, or verified format
    LOW      // Sourced from generic safe fallback
}
