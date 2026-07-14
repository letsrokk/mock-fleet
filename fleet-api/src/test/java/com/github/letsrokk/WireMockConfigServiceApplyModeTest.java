package com.github.letsrokk;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WireMockConfigServiceApplyModeTest {

    @Test
    void futureOnlyDoesNotDeleteActivePod() {
        PodManager podManager = mock(PodManager.class);

        WireMockConfigService.ApplyMode.from("futureOnly").apply("demo", podManager);

        verify(podManager, never()).deleteMock("demo");
    }

    @Test
    void missingModeDefaultsToFutureOnly() {
        PodManager podManager = mock(PodManager.class);

        WireMockConfigService.ApplyMode.from(null).apply("demo", podManager);

        verify(podManager, never()).deleteMock("demo");
    }

    @Test
    void restartActiveDeletesActivePod() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.deleteMock("demo")).thenReturn(PodManager.DeleteMockResult.DELETED);

        WireMockConfigService.ApplyMode.from("restartActive").apply("demo", podManager);

        verify(podManager).deleteMock("demo");
    }

    @Test
    void restartActiveIgnoresMissingActivePod() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.deleteMock("demo")).thenReturn(PodManager.DeleteMockResult.NOT_FOUND);

        WireMockConfigService.ApplyMode.from("restartActive").apply("demo", podManager);

        verify(podManager).deleteMock("demo");
    }

    @Test
    void restartActiveFailsWhenPodDeletionFails() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.deleteMock("demo")).thenReturn(PodManager.DeleteMockResult.FAILED);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> WireMockConfigService.ApplyMode.from("restartActive").apply("demo", podManager));

        assertEquals(500, exception.getResponse().getStatus());
    }

    @Test
    void rejectsUnknownApplyMode() {
        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> WireMockConfigService.ApplyMode.from("now"));

        assertEquals(400, exception.getResponse().getStatus());
    }
}
