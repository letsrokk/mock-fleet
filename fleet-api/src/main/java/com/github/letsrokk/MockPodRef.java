package com.github.letsrokk;

import java.io.Serializable;

public record MockPodRef(String podName, String podIp, String runtimeVersion) implements Serializable {
    public MockPodRef(String podName, String podIp) {
        this(podName, podIp, null);
    }
}
