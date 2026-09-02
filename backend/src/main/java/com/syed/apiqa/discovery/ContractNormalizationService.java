package com.syed.apiqa.discovery;

import com.syed.apiqa.domain.canonical.CanonicalApiModel;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal Contract Normalization Service.
 * Normalizes OpenAPI 3.0, 3.1, and Swagger 2.0 specifications into the lossless CanonicalApiModel.
 * Calculates mathematically defined ContractQualityScore across documentation, schema, example, and security dimensions.
 */
@Service
public class ContractNormalizationService {

    private final OpenApi31Normalizer openApi31Normalizer;

    public ContractNormalizationService(OpenApi31Normalizer openApi31Normalizer) {
        this.openApi31Normalizer = openApi31Normalizer;
    }

    public record NormalizationResult(CanonicalApiModel model, double qualityScore, Map<String, Double> scoreBreakdown) {}

    public NormalizationResult normalize(OpenAPI openAPI, String originalSpecUrl) {
        if (openAPI == null) {
            throw new IllegalArgumentException("OpenAPI specification cannot be null");
        }

        // Apply 3.1 normalization
        openApi31Normalizer.normalize(openAPI);

        CanonicalApiModel model = new CanonicalApiModel();

        // 1. Metadata
        if (openAPI.getInfo() != null) {
            model.getMetadata().setTitle(openAPI.getInfo().getTitle());
            model.getMetadata().setVersion(openAPI.getInfo().getVersion());
            model.getMetadata().setDescription(openAPI.getInfo().getDescription());
        }
        model.getMetadata().setSpecSourceUrl(originalSpecUrl);

        // 2. Servers
        if (openAPI.getServers() != null) {
            for (Server s : openAPI.getServers()) {
                model.getServers().add(new CanonicalApiModel.CanonicalServer(s.getUrl(), s.getDescription()));
            }
            if (!model.getServers().isEmpty()) {
                model.getMetadata().setTargetBaseUrl(model.getServers().get(0).getUrl());
            }
        }

        // 3. Security Schemes
        if (openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            for (Map.Entry<String, SecurityScheme> entry : openAPI.getComponents().getSecuritySchemes().entrySet()) {
                String name = entry.getKey();
                SecurityScheme ss = entry.getValue();
                CanonicalApiModel.CanonicalSecurityScheme css = new CanonicalApiModel.CanonicalSecurityScheme();
                css.setName(name);
                css.setType(ss.getType() != null ? ss.getType().name() : "http");
                css.setScheme(ss.getScheme());
                css.setBearerFormat(ss.getBearerFormat());
                css.setIn(ss.getIn() != null ? ss.getIn().name() : "header");
                css.setParameterName(ss.getName());
                model.getSecuritySchemes().put(name, css);
            }
        }

        // 4. Component Schemas
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            for (Map.Entry<String, Schema> entry : openAPI.getComponents().getSchemas().entrySet()) {
                model.getSchemas().put(entry.getKey(), entry.getValue());
            }
        }

        // 5. Operations
        int totalOps = 0;
        int docCount = 0;
        int schemaCount = 0;
        int exampleCount = 0;
        int securityCount = 0;

        if (openAPI.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();

                List<Parameter> commonParams = pathItem.getParameters() != null ? pathItem.getParameters() : Collections.emptyList();

                totalOps += processOperation(model, "GET", path, pathItem.getGet(), commonParams);
                totalOps += processOperation(model, "POST", path, pathItem.getPost(), commonParams);
                totalOps += processOperation(model, "PUT", path, pathItem.getPut(), commonParams);
                totalOps += processOperation(model, "PATCH", path, pathItem.getPatch(), commonParams);
                totalOps += processOperation(model, "DELETE", path, pathItem.getDelete(), commonParams);
            }
        }

        // Compute Quality Metrics across discovered operations
        for (CanonicalApiModel.CanonicalOperation op : model.getOperations()) {
            if (op.getSummary() != null || op.getDescription() != null) docCount++;
            if (op.getRequestBody() != null || !op.getResponses().isEmpty()) schemaCount++;
            if (op.getRequestBody() != null && op.getRequestBody().getExample() != null) exampleCount++;
            if (!op.getSecurityRequirements().isEmpty()) securityCount++;
        }

        double docScore = totalOps > 0 ? (double) docCount / totalOps * 100.0 : 0.0;
        double schScore = totalOps > 0 ? (double) schemaCount / totalOps * 100.0 : 0.0;
        double exScore = totalOps > 0 ? (double) exampleCount / totalOps * 100.0 : 0.0;
        double secScore = totalOps > 0 ? (double) securityCount / totalOps * 100.0 : 0.0;

        double overallQuality = (0.25 * docScore) + (0.35 * schScore) + (0.25 * exScore) + (0.15 * secScore);

        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("documentationCompleteness", Math.round(docScore * 10.0) / 10.0);
        breakdown.put("schemaCompleteness", Math.round(schScore * 10.0) / 10.0);
        breakdown.put("exampleCompleteness", Math.round(exScore * 10.0) / 10.0);
        breakdown.put("securityCompleteness", Math.round(secScore * 10.0) / 10.0);

        return new NormalizationResult(model, Math.round(overallQuality * 10.0) / 10.0, breakdown);
    }

    private int processOperation(CanonicalApiModel model, String method, String path, Operation op, List<Parameter> commonParams) {
        if (op == null) return 0;

        CanonicalApiModel.CanonicalOperation cop = new CanonicalApiModel.CanonicalOperation();
        cop.setMethod(method);
        cop.setPath(path);
        cop.setOperationId(op.getOperationId() != null ? op.getOperationId() : method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"));
        cop.setSummary(op.getSummary());
        cop.setDescription(op.getDescription());
        cop.setTags(op.getTags() != null ? new ArrayList<>(op.getTags()) : new ArrayList<>());

        // Risk classification
        if ("DELETE".equalsIgnoreCase(method)) {
            cop.setRiskClassification("DELETE");
        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            cop.setRiskClassification("WRITE");
        } else {
            cop.setRiskClassification("READ");
        }

        // Parameters
        List<Parameter> allParams = new ArrayList<>(commonParams);
        if (op.getParameters() != null) allParams.addAll(op.getParameters());

        for (Parameter p : allParams) {
            CanonicalApiModel.CanonicalParameter cp = new CanonicalApiModel.CanonicalParameter();
            cp.setName(p.getName());
            cp.setIn(p.getIn());
            cp.setRequired(Boolean.TRUE.equals(p.getRequired()));
            cp.setSchema(p.getSchema());
            cp.setExample(p.getExample());
            cop.getParameters().add(cp);
        }

        // Request Body
        if (op.getRequestBody() != null) {
            RequestBody rb = op.getRequestBody();
            CanonicalApiModel.CanonicalRequestBody crb = new CanonicalApiModel.CanonicalRequestBody();
            crb.setRequired(Boolean.TRUE.equals(rb.getRequired()));

            if (rb.getContent() != null) {
                for (Map.Entry<String, MediaType> mtEntry : rb.getContent().entrySet()) {
                    crb.getMediaTypes().add(mtEntry.getKey());
                    if (crb.getSchema() == null && mtEntry.getValue().getSchema() != null) {
                        crb.setSchema(mtEntry.getValue().getSchema());
                    }
                    if (crb.getExample() == null && mtEntry.getValue().getExample() != null) {
                        crb.setExample(mtEntry.getValue().getExample());
                    }
                }
            }
            cop.setRequestBody(crb);
        }

        // Responses
        if (op.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> respEntry : op.getResponses().entrySet()) {
                String code = respEntry.getKey();
                ApiResponse ar = respEntry.getValue();
                CanonicalApiModel.CanonicalResponse cresp = new CanonicalApiModel.CanonicalResponse();
                cresp.setStatusCode(code);
                cresp.setDescription(ar.getDescription());

                if (ar.getContent() != null) {
                    for (Map.Entry<String, MediaType> mtEntry : ar.getContent().entrySet()) {
                        cresp.getMediaTypes().add(mtEntry.getKey());
                        if (cresp.getSchema() == null && mtEntry.getValue().getSchema() != null) {
                            cresp.setSchema(mtEntry.getValue().getSchema());
                        }
                    }
                }
                cop.getResponses().put(code, cresp);
            }
        }

        model.getOperations().add(cop);
        return 1;
    }
}
