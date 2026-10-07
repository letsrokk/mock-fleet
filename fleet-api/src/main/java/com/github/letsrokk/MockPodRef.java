package com.github.letsrokk;

import java.io.Serializable;

public record MockPodRef(String podName, String podIp, String runtimeVersion, boolean pinned) implements Serializable {
    public MockPodRef(String podName, String podIp, String runtimeVersion) {
        this(podName, podIp, runtimeVersion, false);
    }

    public MockPodRef(String podName, String podIp) {
        this(podName, podIp, null);
    }
}
