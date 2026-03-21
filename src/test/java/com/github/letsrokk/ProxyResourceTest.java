package com.github.letsrokk;

import com.github.letsrokk.exceptions.MockIdNotFound;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ProxyResourceTest {

    @InjectMock
    PodManager podManager;

    @InjectMock
    ProxyClientFactory proxyClientFactory;

    private ProxyClient proxyClient;

    @BeforeEach
    void setUp() {
        proxyClient = mock(ProxyClient.class);
        when(proxyClientFactory.createClient(any())).thenReturn(proxyClient);
    }

    @Test
    void proxiesNestedGetRequestsWithPathAndQueryParameters() {
        when(podManager.getUpstreamBaseUrl("demo.example.test"))
                .thenReturn("http://mock-fleet-demo.test.svc.cluster.local:8080");
        when(proxyClient.forwardGet(any(), any())).thenReturn(Response.ok("ok").build());

        given()
                .header("Host", "demo.example.test")
                .queryParam("alpha", "1")
                .queryParam("beta", "two")
        .when()
                .get("/nested/path")
        .then()
                .statusCode(200)
                .body(is("ok"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultivaluedMap<String, String>> paramsCaptor = ArgumentCaptor.forClass(MultivaluedMap.class);
        verify(proxyClientFactory).createClient("http://mock-fleet-demo.test.svc.cluster.local:8080");
        verify(proxyClient).forwardGet(pathCaptor.capture(), paramsCaptor.capture());

        assertEquals("nested/path", pathCaptor.getValue());
        assertEquals("1", paramsCaptor.getValue().getFirst("alpha"));
        assertEquals("two", paramsCaptor.getValue().getFirst("beta"));
    }

    @Test
    void returnsControlledBadRequestForInvalidHostHeader() {
        when(podManager.getUpstreamBaseUrl("!!!:8080"))
                .thenThrow(new MockIdNotFound("Unable to extract mock id from host '!!!:8080'."));

        given()
                .header("Host", "!!!:8080")
        .when()
                .get("/anything")
        .then()
                .statusCode(400)
                .body(containsString("Unable to extract mock id"));
    }

    @Test
    void returnsMethodNotAllowedForUnsupportedProxyMethod() {
        ProxyResource resource = new ProxyResource();
        resource.proxyClientFactory = proxyClientFactory;

        Response response = resource.proxyRequest(
                "TRACE",
                "http://mock-fleet-demo.test.svc.cluster.local:8080",
                "test",
                new jakarta.ws.rs.core.MultivaluedHashMap<>(),
                null);

        assertEquals(405, response.getStatus());
    }

    @Test
    void forwardsUpstreamClientErrorsWithoutMaskingThem() {
        when(podManager.getUpstreamBaseUrl("demo.example.test"))
                .thenReturn("http://mock-fleet-demo.test.svc.cluster.local:8080");
        when(proxyClient.forwardGet(eq("missing"), any())).thenReturn(Response.status(404).entity("missing").build());

        given()
                .header("Host", "demo.example.test")
        .when()
                .get("/missing")
        .then()
                .statusCode(404)
                .body(is("missing"));
    }
}
