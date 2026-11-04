package com.github.letsrokk;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(LocationRewriteDisabledTest.DisabledProfile.class)
class LocationRewriteDisabledTest {

    @InjectMock
    FleetApiClient fleetApiClient;

    @Test
    void preservesRootRelativeLocationsWhenDisabled() {
        Vertx vertx = Vertx.vertx();
        try {
            HttpServer upstream = vertx.createHttpServer()
                    .requestHandler(request -> request.response().setStatusCode(307)
                            .putHeader("Location", "/next?c=d").end())
                    .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
            when(fleetApiClient.resolveUpstreamBaseUrl("demo"))
                    .thenReturn(Future.succeededFuture("http://127.0.0.1:" + upstream.actualPort()));

            given().header("Host", "mock-fleet.localhost").redirects().follow(false)
                    .get("/demo/start")
                    .then().statusCode(307).header("Location", "/next?c=d");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("mock-fleet.routing.mode", "PATH",
                    "mock-fleet.routing.rewrite-location-headers", "false");
        }
    }
}
