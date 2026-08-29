package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class WireMockResourcePolicy {

    private static final Set<String> SUPPORTED_RESOURCES = Set.of("cpu", "memory");
    private static final String DEFAULT_CPU_FLOOR = "100m";
    private static final String DEFAULT_MEMORY_FLOOR = "128Mi";
    private static final String DEFAULT_CPU_CEILING = "4";
    private static final String DEFAULT_MEMORY_CEILING = "4Gi";

    private final Map<String, Quantity> requestFloors;
    private final Map<String, Quantity> limitCeilings;

    @Inject
    public WireMockResourcePolicy(MockFleetConfig config) {
        MockFleetConfig.WireMockResourcePolicyConfig policy = config.wiremockResourcePolicy();
        MockFleetConfig.ResourceValues floors = policy == null ? null : policy.requestFloor();
        MockFleetConfig.ResourceValues ceilings = policy == null ? null : policy.limitCeiling();
        this.requestFloors = Map.of(
                "cpu", configuredQuantity(floors == null ? null : floors.cpu(), DEFAULT_CPU_FLOOR,
                        "mock-fleet.wiremock-resource-policy.request-floor.cpu"),
                "memory", configuredQuantity(floors == null ? null : floors.memory(), DEFAULT_MEMORY_FLOOR,
                        "mock-fleet.wiremock-resource-policy.request-floor.memory"));
        this.limitCeilings = Map.of(
                "cpu", configuredQuantity(ceilings == null ? null : ceilings.cpu(), DEFAULT_CPU_CEILING,
                        "mock-fleet.wiremock-resource-policy.limit-ceiling.cpu"),
                "memory", configuredQuantity(ceilings == null ? null : ceilings.memory(), DEFAULT_MEMORY_CEILING,
                        "mock-fleet.wiremock-resource-policy.limit-ceiling.memory"));
        SUPPORTED_RESOURCES.forEach(name -> {
            if (amount(requestFloors.get(name), "configured resource policy")
                    .compareTo(amount(limitCeilings.get(name), "configured resource policy")) > 0) {
                throw new IllegalStateException("WireMock resource request floor exceeds limit ceiling: " + name);
            }
        });
    }

    public ResourceRequirements normalizeAndValidate(ResourceRequirements baseline,
                                                      WireMockConfigService.ResourceData requested) {
        Map<String, Quantity> requests = quantities(baseline == null ? null : baseline.getRequests());
        Map<String, Quantity> limits = quantities(baseline == null ? null : baseline.getLimits());
        if (requested != null) {
            overlay(requests, requested.requests(), "requests");
            overlay(limits, requested.limits(), "limits");
        }
        ResourceRequirements effective = new ResourceRequirementsBuilder()
                .withRequests(requests)
                .withLimits(limits)
                .build();
        validateEffective(effective);
        return effective;
    }

    public void validateEffective(ResourceRequirements effective) {
        Map<String, Quantity> requests = effective == null ? Map.of() : quantities(effective.getRequests());
        Map<String, Quantity> limits = effective == null ? Map.of() : quantities(effective.getLimits());
        rejectUnsupported(requests);
        rejectUnsupported(limits);
        if (!requests.keySet().containsAll(SUPPORTED_RESOURCES)
                || !limits.keySet().containsAll(SUPPORTED_RESOURCES)) {
            throw invalid("WireMock resources require requests and limits for cpu and memory.", Map.of());
        }

        for (String name : SUPPORTED_RESOURCES) {
            BigDecimal request = amount(requests.get(name), "requests." + name);
            BigDecimal limit = amount(limits.get(name), "limits." + name);
            if (request.compareTo(amount(requestFloors.get(name), "request floor " + name)) < 0) {
                throw invalid("WireMock resource request is below the configured floor: " + name,
                        Map.of("resource", name));
            }
            if (limit.compareTo(amount(limitCeilings.get(name), "limit ceiling " + name)) > 0) {
                throw invalid("WireMock resource limit is above the configured ceiling: " + name,
                        Map.of("resource", name));
            }
            if (request.compareTo(limit) > 0) {
                throw invalid("WireMock resource request must not exceed its limit: " + name,
                        Map.of("resource", name));
            }
        }
    }

    private void overlay(Map<String, Quantity> target, Map<String, String> requested, String field) {
        if (requested == null) {
            return;
        }
        requested.forEach((name, value) -> {
            if (!SUPPORTED_RESOURCES.contains(name)) {
                throw invalid("Unsupported WireMock resource: " + name, Map.of("resource", String.valueOf(name)));
            }
            target.put(name, parseRequested(value, field + "." + name));
        });
    }

    private Quantity parseRequested(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("Invalid WireMock resource quantity: " + field, Map.of("field", field));
        }
        Quantity quantity;
        try {
            quantity = Quantity.parse(value.trim());
            Quantity.getAmountInBytes(quantity);
        } catch (RuntimeException error) {
            throw invalid("Invalid WireMock resource quantity: " + field, Map.of("field", field));
        }
        return quantity;
    }

    private void rejectUnsupported(Map<String, Quantity> values) {
        values.keySet().stream()
                .filter(name -> !SUPPORTED_RESOURCES.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw invalid("Unsupported WireMock resource: " + name, Map.of("resource", name));
                });
    }

    private Map<String, Quantity> quantities(Map<String, Quantity> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private Quantity configuredQuantity(String value, String fallback, String field) {
        String configured = value == null || value.isBlank() ? fallback : value.trim();
        try {
            Quantity quantity = Quantity.parse(configured);
            Quantity.getAmountInBytes(quantity);
            return quantity;
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid quantity configured for " + field + ".", error);
        }
    }

    private BigDecimal amount(Quantity quantity, String field) {
        try {
            return Quantity.getAmountInBytes(quantity);
        } catch (RuntimeException error) {
            throw invalid("Invalid WireMock resource quantity: " + field, Map.of("field", field));
        }
    }

    private ApiException invalid(String message, Map<String, Object> details) {
        return ApiException.badRequest("INVALID_RESOURCES", message, details);
    }
}
