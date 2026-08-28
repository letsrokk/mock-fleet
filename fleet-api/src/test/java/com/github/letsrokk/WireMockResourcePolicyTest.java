package com.github.letsrokk;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WireMockResourcePolicyTest {

    private WireMockResourcePolicy policy;
    private ResourceRequirements baseline;

    @BeforeEach
    void setUp() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        MockFleetConfig.WireMockResourcePolicyConfig resourcePolicy =
                mock(MockFleetConfig.WireMockResourcePolicyConfig.class);
        MockFleetConfig.ResourceValues requestFloor = mock(MockFleetConfig.ResourceValues.class);
        MockFleetConfig.ResourceValues limitCeiling = mock(MockFleetConfig.ResourceValues.class);
        when(config.wiremockResourcePolicy()).thenReturn(resourcePolicy);
        when(resourcePolicy.requestFloor()).thenReturn(requestFloor);
        when(resourcePolicy.limitCeiling()).thenReturn(limitCeiling);
        when(requestFloor.cpu()).thenReturn("250m");
        when(requestFloor.memory()).thenReturn("256Mi");
        when(limitCeiling.cpu()).thenReturn("2");
        when(limitCeiling.memory()).thenReturn("2Gi");
        policy = new WireMockResourcePolicy(config);
        baseline = resources("500m", "512Mi", "1", "1Gi");
    }

    @Test
    void omittedResourcesInheritTheCompleteBaseline() {
        assertEquals(baseline, policy.normalizeAndValidate(baseline, null));
    }

    @Test
    void explicitEmptyResourcesCannotEraseTheBaseline() {
        ResourceRequirements normalized = policy.normalizeAndValidate(baseline,
                new WireMockConfigService.ResourceData(Map.of(), Map.of()));

        assertResources(normalized, "500m", "512Mi", "1", "1Gi");
    }

    @Test
    void partialMapsOverrideOnlySubmittedKeys() {
        ResourceRequirements normalized = policy.normalizeAndValidate(baseline,
                new WireMockConfigService.ResourceData(
                        Map.of("cpu", "750m"),
                        Map.of("memory", "1536Mi")));

        assertResources(normalized, "750m", "512Mi", "1", "1536Mi");
    }

    @Test
    void rejectsUnsupportedResourceNames() {
        assertInvalid(new WireMockConfigService.ResourceData(
                        Map.of("ephemeral-storage", "1Gi"), Map.of()),
                "Unsupported WireMock resource: ephemeral-storage");
    }

    @Test
    void rejectsMalformedQuantities() {
        assertInvalid(new WireMockConfigService.ResourceData(
                        Map.of("cpu", "not-a-quantity"), Map.of()),
                "Invalid WireMock resource quantity: requests.cpu");
    }

    @Test
    void rejectsLimitsBelowRequests() {
        assertInvalid(new WireMockConfigService.ResourceData(
                        Map.of("cpu", "1500m"), Map.of()),
                "WireMock resource request must not exceed its limit: cpu");
    }

    @Test
    void rejectsRequestsBelowTheConfiguredFloor() {
        assertInvalid(new WireMockConfigService.ResourceData(
                        Map.of("memory", "128Mi"), Map.of()),
                "WireMock resource request is below the configured floor: memory");
    }

    @Test
    void rejectsLimitsAboveTheConfiguredCeiling() {
        assertInvalid(new WireMockConfigService.ResourceData(
                        Map.of(), Map.of("cpu", "3")),
                "WireMock resource limit is above the configured ceiling: cpu");
    }

    @Test
    void rejectsAnIncompleteEffectiveResourceSet() {
        ResourceRequirements incomplete = new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity("500m"))
                .addToLimits("cpu", new Quantity("1"))
                .build();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> policy.validateEffective(incomplete));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals("WireMock resources require requests and limits for cpu and memory.", exception.getMessage());
    }

    @Test
    void podFactoryRevalidatesEffectiveResourcesBeforeBuildingThePod() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodFactory podFactory = new PodFactory(config);
        ResourceRequirements incomplete = new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity("500m"))
                .addToLimits("cpu", new Quantity("1"))
                .build();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of(), incomplete));

        assertEquals("WireMock resources require requests and limits for cpu and memory.", exception.getMessage());
    }

    @Test
    void podFactoryExposesOnlyTheValidatedConstructionPath() {
        List<List<Class<?>>> publicConstructionPaths = Arrays.stream(PodFactory.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("createPodSpec"))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getParameterTypes)
                .map(Arrays::asList)
                .toList();

        assertEquals(List.of(List.of(String.class, String.class, List.class, ResourceRequirements.class)),
                publicConstructionPaths);
    }

    @Test
    void podFactoryRejectsMissingEffectiveResourcesBeforeBuildingThePod() {
        MockFleetConfig config = mock(MockFleetConfig.class);
        PodFactory podFactory = new PodFactory(config);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> podFactory.createPodSpec("mock-fleet-demo-", "demo", List.of(), null));

        assertEquals("WireMock resources require requests and limits for cpu and memory.", exception.getMessage());
    }

    private void assertInvalid(WireMockConfigService.ResourceData requested, String expectedMessage) {
        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> policy.normalizeAndValidate(baseline, requested));

        assertEquals(400, exception.getResponse().getStatus());
        assertEquals(expectedMessage, exception.getMessage());
    }

    private ResourceRequirements resources(String requestCpu, String requestMemory,
                                           String limitCpu, String limitMemory) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(requestCpu))
                .addToRequests("memory", new Quantity(requestMemory))
                .addToLimits("cpu", new Quantity(limitCpu))
                .addToLimits("memory", new Quantity(limitMemory))
                .build();
    }

    private void assertResources(ResourceRequirements resources, String requestCpu, String requestMemory,
                                 String limitCpu, String limitMemory) {
        assertEquals(new Quantity(requestCpu), resources.getRequests().get("cpu"));
        assertEquals(new Quantity(requestMemory), resources.getRequests().get("memory"));
        assertEquals(new Quantity(limitCpu), resources.getLimits().get("cpu"));
        assertEquals(new Quantity(limitMemory), resources.getLimits().get("memory"));
        assertEquals(2, resources.getRequests().size());
        assertEquals(2, resources.getLimits().size());
    }
}
