package com.github.letsrokk;

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
        when(podManager.status("demo")).thenReturn(new PodManager.MockPodStatus(
                "demo", null, MockLifecycleStatus.STOPPED, null));

        MockLifecycleStatus lifecycle = WireMockConfigService.ApplyMode.from("futureOnly").apply("demo", podManager);

        verify(podManager, never()).deleteMock("demo");
        assertEquals(MockLifecycleStatus.STOPPED, lifecycle);
    }

    @Test
    void missingModeDefaultsToFutureOnly() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.status("demo")).thenReturn(new PodManager.MockPodStatus(
                "demo", null, MockLifecycleStatus.STOPPED, null));

        WireMockConfigService.ApplyMode.from(null).apply("demo", podManager);

        verify(podManager, never()).deleteMock("demo");
    }

    @Test
    void restartActiveReplacesAnActivePodAsynchronously() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.restartActive("demo")).thenReturn(new PodManager.MockPodStatus(
                "demo", null, MockLifecycleStatus.STARTING, null));

        MockLifecycleStatus lifecycle = WireMockConfigService.ApplyMode.from("restartActive").apply("demo", podManager);

        verify(podManager).restartActive("demo");
        assertEquals(MockLifecycleStatus.STARTING, lifecycle);
    }

    @Test
    void restartActiveReturnsStoppedWhenMockWasNotActive() {
        PodManager podManager = mock(PodManager.class);
        when(podManager.restartActive("demo")).thenReturn(new PodManager.MockPodStatus(
                "demo", null, MockLifecycleStatus.STOPPED, null));

        MockLifecycleStatus lifecycle = WireMockConfigService.ApplyMode.from("restartActive").apply("demo", podManager);

        assertEquals(MockLifecycleStatus.STOPPED, lifecycle);
    }

    @Test
    void rejectsUnknownApplyMode() {
        jakarta.ws.rs.WebApplicationException exception = assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> WireMockConfigService.ApplyMode.from("now"));

        assertEquals(400, exception.getResponse().getStatus());
    }
}
