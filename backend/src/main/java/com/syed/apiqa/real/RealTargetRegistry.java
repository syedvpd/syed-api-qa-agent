package com.syed.apiqa.real;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RealTargetRegistry - Internal compatibility harness for testing real-world public APIs
 * and OpenAPI/Swagger specifications across diverse versions, scales, schemas, and architectures.
 */
public class RealTargetRegistry {

    public enum SpecFormat {
        OPENAPI_3_0,
        OPENAPI_3_1,
        SWAGGER_2_0,
        YAML_OR_JSON
    }

    public enum SafetyClassification {
        SAFE_READ_ONLY,
        PUBLIC_SANDBOX_WRITE,
        AUTH_REQUIRED,
        DESTRUCTIVE_UNSAFE
    }

    public static class RealTarget implements Serializable {
        private final String id;
        private final String name;
        private final String specUrl;
        private final String fallbackBaseUrl;
        private final SpecFormat specFormat;
        private final SafetyClassification safety;
        private final String authStrategy;
        private final int expectedMinOperations;
        private final String description;

        public RealTarget(String id, String name, String specUrl, String fallbackBaseUrl,
                          SpecFormat specFormat, SafetyClassification safety,
                          String authStrategy, int expectedMinOperations, String description) {
            this.id = id;
            this.name = name;
            this.specUrl = specUrl;
            this.fallbackBaseUrl = fallbackBaseUrl;
            this.specFormat = specFormat;
            this.safety = safety;
            this.authStrategy = authStrategy;
            this.expectedMinOperations = expectedMinOperations;
            this.description = description;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getSpecUrl() { return specUrl; }
        public String getFallbackBaseUrl() { return fallbackBaseUrl; }
        public SpecFormat getSpecFormat() { return specFormat; }
        public SafetyClassification getSafety() { return safety; }
        public String getAuthStrategy() { return authStrategy; }
        public int getExpectedMinOperations() { return expectedMinOperations; }
        public String getDescription() { return description; }
    }

    public static List<RealTarget> getStandardRealTargets() {
        List<RealTarget> targets = new ArrayList<>();

        // 1. Control Target: Swagger Petstore v3 (OpenAPI 3.0)
        targets.add(new RealTarget(
                "petstore-v3",
                "Swagger Petstore (OpenAPI 3.0)",
                "https://petstore3.swagger.io/api/v3/openapi.json",
                "https://petstore3.swagger.io/api/v3",
                SpecFormat.OPENAPI_3_0,
                SafetyClassification.PUBLIC_SANDBOX_WRITE,
                "API_KEY",
                15,
                "Standard reference OpenAPI 3.0 specification with CRUD operations and security schemes"
        ));

        // 2. Swagger 2.0 Target: Swagger Petstore v2
        targets.add(new RealTarget(
                "petstore-v2",
                "Swagger Petstore (Swagger 2.0)",
                "https://petstore.swagger.io/v2/swagger.json",
                "https://petstore.swagger.io/v2",
                SpecFormat.SWAGGER_2_0,
                SafetyClassification.PUBLIC_SANDBOX_WRITE,
                "API_KEY",
                18,
                "Legacy Swagger 2.0 JSON contract with basePath and definitions"
        ));

        // 3. HTTP Validation Target: Httpbin Specs
        targets.add(new RealTarget(
                "httpbin",
                "Httpbin HTTP Testing API",
                "https://raw.githubusercontent.com/Azure/azure-rest-api-specs/main/specification/apimanagement/resource-manager/Microsoft.ApiManagement/stable/2022-08-01/examples/ApiManagementCreateApiWithOpenIdConnect.json",
                "https://httpbin.org",
                SpecFormat.OPENAPI_3_0,
                SafetyClassification.SAFE_READ_ONLY,
                "NO_AUTH",
                1,
                "Public HTTP testing target verifying methods, status codes, and headers"
        ));

        // 4. Unknown Live REST Target: JSONPlaceholder
        targets.add(new RealTarget(
                "jsonplaceholder",
                "JSONPlaceholder Live REST API",
                "https://syed-api-testing-agent.onrender.com/api/specs/jsonplaceholder.json",
                "https://jsonplaceholder.typicode.com",
                SpecFormat.OPENAPI_3_0,
                SafetyClassification.PUBLIC_SANDBOX_WRITE,
                "NO_AUTH",
                12,
                "Live REST API target supporting full CRUD operations, nested comments, and pagination"
        ));

        return targets;
    }
}
