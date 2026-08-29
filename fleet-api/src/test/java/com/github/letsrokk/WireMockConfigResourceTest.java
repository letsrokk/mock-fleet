package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class WireMockConfigResourceTest {

    @InjectMock
    WireMockConfigService configService;

    @Test
    void getsConfig() {
        when(configService.view()).thenReturn(configView());

        given()
        .when()
                .get("/__fleet/api/config")
        .then()
                .statusCode(200)
                .body("resourceVersion", is("42"))
                .body("mockIds[0]", is("demo"))
                .body("savedMockIds[0]", is("demo"))
                .body("mocks[0].mockId", is("demo"))
                .body("mocks[0].lifecycle", is("RUNNING"))
                .body("mocks[0].active", nullValue())
                .body("mocks[0].user.resources", nullValue())
                .body("mocks[0].effective.options[0]", is("--verbose"))
                .body("wireMock.configuredImage", is("wiremock/wiremock:3.13.2-2"))
                .body("wireMock.version", is("3.13.2"))
                .body("wireMock.minimumSupportedVersion", is("3.0.0"))
                .body("wireMock.maximumResearchedVersion", is("3.13.2"))
                .body("wireMock.rangeStatus", is("supported"));

        verify(configService).view();
    }

    @Test
    void getsVersionedPublicOptionCatalog() {
        when(configService.optionCatalog("3.7.0")).thenReturn(optionCatalog("3.7.0", "supported"));

        given()
                .queryParam("version", "3.7.0")
        .when()
                .get("/__fleet/api/config/options")
        .then()
                .statusCode(200)
                .body("size()", is(3))
                .body("wireMockVersion", is("3.7.0"))
                .body("catalogStatus", is("supported"))
                .body("options[0].name", is("--verbose"))
                .body("options[0].available", nullValue())
                .body("options[0].compatibility", nullValue())
                .body("options[0].versionRanges", nullValue());

        verify(configService).optionCatalog("3.7.0");
    }

    @Test
    void getsDefaultOptionCatalogForAnOmittedVersion() {
        when(configService.optionCatalog(isNull())).thenReturn(optionCatalog("3.13.2", "supported"));

        given()
        .when()
                .get("/__fleet/api/config/options")
        .then()
                .statusCode(200)
                .body("wireMockVersion", is("3.13.2"));

        verify(configService).optionCatalog(isNull());
    }

    @Test
    void rejectsInvalidCatalogVersionsWithoutInvokingConfigurationMutation() {
        doThrow(ApiException.badRequest("INVALID_WIREMOCK_VERSION",
                "WireMock version must be an exact WireMock 3.x semantic version.", Map.of("version", "4.0.0")))
                .when(configService).optionCatalog("4.0.0");

        given()
                .queryParam("version", "4.0.0")
        .when()
                .get("/__fleet/api/config/options")
        .then()
                .statusCode(400)
                .body("code", is("INVALID_WIREMOCK_VERSION"))
                .body("retryable", is(false))
                .body("stateMayHaveChanged", is(false));

        verify(configService).optionCatalog("4.0.0");
        verifyNoMoreInteractions(configService);
    }

    @Test
    void upsertsMockConfig() {
        WireMockConfigService.ConfigUpdateRequest request = new WireMockConfigService.ConfigUpdateRequest(
                "42",
                List.of("--verbose"),
                new WireMockConfigService.ResourceData(Map.of(), Map.of()),
                "restartActive");
        when(configService.upsertMockConfig(eq("demo"), eq(request))).thenReturn(mutation(
                "restartActive", MockLifecycleStatus.STARTING));

        given()
                .contentType("application/json")
                .body(request)
        .when()
                .put("/__fleet/api/config/demo")
        .then()
                .statusCode(200)
                .body("config.mockIds[0]", is("demo"))
                .body("apply.mockId", is("demo"))
                .body("apply.mode", is("restartActive"))
                .body("apply.lifecycle", is("STARTING"));

        verify(configService).upsertMockConfig(eq("demo"), eq(request));
    }

    @Test
    void acceptsNullResourcesToRetainInheritance() {
        WireMockConfigService.ConfigUpdateRequest request = new WireMockConfigService.ConfigUpdateRequest(
                "42",
                List.of(),
                null,
                "futureOnly");
        when(configService.upsertMockConfig(eq("demo"), eq(request))).thenReturn(mutation(
                "futureOnly", MockLifecycleStatus.STOPPED));

        given()
                .contentType("application/json")
                .body("""
                        {"resourceVersion":"42","options":[],"resources":null,"applyMode":"futureOnly"}
                        """)
        .when()
                .put("/__fleet/api/config/demo")
        .then()
                .statusCode(200);

        verify(configService).upsertMockConfig(eq("demo"), eq(request));
    }

    @Test
    void deletesMockConfig() {
        WireMockConfigService.ConfigUpdateRequest request = new WireMockConfigService.ConfigUpdateRequest(
                "42",
                null,
                null,
                "futureOnly");
        when(configService.deleteMockConfig(eq("demo"), eq(request))).thenReturn(mutation(
                "futureOnly", MockLifecycleStatus.STOPPED));

        given()
                .contentType("application/json")
                .body(request)
        .when()
                .delete("/__fleet/api/config/demo")
        .then()
                .statusCode(200)
                .body("config.mockIds[0]", is("demo"))
                .body("apply.lifecycle", is("STOPPED"));

        verify(configService).deleteMockConfig(eq("demo"), eq(request));
    }

    @Test
    void rejectsInvalidMockIdOnUpsert() {
        WireMockConfigService.ConfigUpdateRequest request = new WireMockConfigService.ConfigUpdateRequest(
                "42",
                List.of("--verbose"),
                new WireMockConfigService.ResourceData(Map.of(), Map.of()),
                "futureOnly");
        doThrow(ApiException.badRequest("INVALID_MOCK_ID", WireMockConfigService.MOCK_ID_VALIDATION_MESSAGE,
                Map.of("mockId", "demo_1")))
                .when(configService).upsertMockConfig(eq("demo_1"), eq(request));

        given()
                .contentType("application/json")
                .body(request)
        .when()
                .put("/__fleet/api/config/demo_1")
        .then()
                .statusCode(400)
                .body("code", is("INVALID_MOCK_ID"))
                .body("retryable", is(false))
                .body("stateMayHaveChanged", is(false));

        verify(configService).upsertMockConfig(eq("demo_1"), eq(request));
    }

    @Test
    void rejectsInvalidMockIdOnDelete() {
        WireMockConfigService.ConfigUpdateRequest request = new WireMockConfigService.ConfigUpdateRequest(
                "42",
                null,
                null,
                "futureOnly");
        doThrow(ApiException.badRequest("INVALID_MOCK_ID", WireMockConfigService.MOCK_ID_VALIDATION_MESSAGE,
                Map.of("mockId", "demo_1")))
                .when(configService).deleteMockConfig(eq("demo_1"), eq(request));

        given()
                .contentType("application/json")
                .body(request)
        .when()
                .delete("/__fleet/api/config/demo_1")
        .then()
                .statusCode(400)
                .body("code", is("INVALID_MOCK_ID"));

        verify(configService).deleteMockConfig(eq("demo_1"), eq(request));
    }

    private WireMockConfigService.ConfigView configView() {
        WireMockConfigService.ResourceData resources = new WireMockConfigService.ResourceData(Map.of(), Map.of());
        WireMockConfigService.ConfigData empty = new WireMockConfigService.ConfigData(List.of(), resources);
        WireMockConfigService.ConfigData user = new WireMockConfigService.ConfigData(List.of(), null);
        WireMockConfigService.ConfigData effective = new WireMockConfigService.ConfigData(List.of("--verbose"), resources);
        WireMockConfigService.MockConfigView mock = new WireMockConfigService.MockConfigView(
                "demo",
                MockLifecycleStatus.RUNNING,
                empty,
                user,
                effective);
        return new WireMockConfigService.ConfigView(
                "42",
                List.of("demo"),
                List.of("demo"),
                List.of(mock),
                new WireMockConfigService.WireMockVersionView(
                        "wiremock/wiremock:3.13.2-2", "3.13.2", "3.0.0", "3.13.2", "supported"),
                new WireMockConfigService.RoutingView("HOST", "mock-fleet.localhost"));
    }

    private WireMockConfigService.OptionCatalogView optionCatalog(String version, String status) {
        return new WireMockConfigService.OptionCatalogView(version, status, List.of(
                new WireMockConfigService.PublicOptionDefinition(
                        "--verbose", "Verbose logging", "flag", "Logging", "Log more detail to stdout.",
                        List.of(), null, null)));
    }

    private WireMockConfigService.ConfigMutationResult mutation(String mode, MockLifecycleStatus lifecycle) {
        return new WireMockConfigService.ConfigMutationResult(configView(),
                new WireMockConfigService.ApplyResult("demo", mode, lifecycle));
    }
}
