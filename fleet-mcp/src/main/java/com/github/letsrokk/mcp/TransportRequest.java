package com.github.letsrokk.mcp;

import io.vertx.core.http.HttpMethod;
import java.util.List;
import java.util.Map;

public record TransportRequest(HttpMethod method, String target, Map<String, List<String>> headers, byte[] body) {

    public TransportRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
