package com.github.letsrokk;

import java.io.Serializable;

public record MockPodRef(String podName, String podIp) implements Serializable {
}
