package com.github.letsrokk;

import java.io.Serial;
import java.io.Serializable;

public record MockPodLifecycle(String attemptId, String podName, MockLifecycleStatus status,
                               String message, long startedAtEpochMillis) implements Serializable {

    @Serial
    private static final long serialVersionUID = 0L;

    public MockPodLifecycle(String attemptId, String podName, MockLifecycleStatus status, String message) {
        this(attemptId, podName, status, message, 0L);
    }

    public static MockPodLifecycle starting(String podName) {
        return starting(null, podName);
    }

    public static MockPodLifecycle starting(String attemptId, String podName) {
        return starting(attemptId, podName, 0L);
    }

    public static MockPodLifecycle starting(String attemptId, String podName, long startedAtEpochMillis) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.STARTING, null, startedAtEpochMillis);
    }

    public static MockPodLifecycle running(String attemptId, String podName) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.RUNNING, null, 0L);
    }

    public static MockPodLifecycle failed(String podName, String message) {
        return failed(null, podName, message);
    }

    public static MockPodLifecycle failed(String attemptId, String podName, String message) {
        return new MockPodLifecycle(attemptId, podName, MockLifecycleStatus.FAILED, message, 0L);
    }

    public static MockPodLifecycle stopped() {
        return stopped(null);
    }

    public static MockPodLifecycle stopped(String podName) {
        return new MockPodLifecycle(null, podName, MockLifecycleStatus.STOPPED, null, 0L);
    }
}
