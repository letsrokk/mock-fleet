package com.github.letsrokk;

import java.io.Serializable;

public record MockPodLifecycle(String attemptId, String podName, MockLifecycleStatus status,
                               String message) implements Serializable {

    public static MockPodLifecycle starting(String podName) {
        return starting(null, podName);
    }

    public static MockPodLifecycle starting(String attemptId, String podName) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.STARTING, null);
    }

    public static MockPodLifecycle running(String attemptId, String podName) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.RUNNING, null);
    }

    public static MockPodLifecycle failed(String podName, String message) {
        return failed(null, podName, message);
    }

    public static MockPodLifecycle failed(String attemptId, String podName, String message) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.FAILED, message);
    }

    public static MockPodLifecycle stopped() {
        return stopped(null);
    }

    public static MockPodLifecycle stopped(String podName) {
        return new MockPodLifecycle(null, podName, MockLifecycleStatus.STOPPED, null);
    }
}
