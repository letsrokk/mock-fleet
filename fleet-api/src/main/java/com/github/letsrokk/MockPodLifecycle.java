package com.github.letsrokk;

import java.io.Serializable;

public record MockPodLifecycle(String podName, MockLifecycleStatus status, String message) implements Serializable {

    public static MockPodLifecycle starting(String podName) {
        return new MockPodLifecycle(podName, MockLifecycleStatus.STARTING, null);
    }

    public static MockPodLifecycle failed(String podName, String message) {
        return new MockPodLifecycle(podName, MockLifecycleStatus.FAILED, message);
    }
}
