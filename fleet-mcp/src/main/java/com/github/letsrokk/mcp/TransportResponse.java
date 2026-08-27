package com.github.letsrokk.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public record TransportResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public TransportResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
