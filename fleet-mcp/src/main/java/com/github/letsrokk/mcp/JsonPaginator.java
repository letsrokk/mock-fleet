package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

public final class JsonPaginator {

    private JsonPaginator() {
    }

    public static ObjectNode page(ObjectMapper mapper, JsonNode source, String collectionField, int limit, int offset) {
        JsonNode collection = source.isArray() ? source : source.path(collectionField);
        if (!collection.isArray()) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                    "Upstream response is missing collection field " + collectionField, false,
                    Map.of("field", collectionField));
        }

        ObjectNode result = source instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        ArrayNode page = mapper.createArrayNode();
        int start = Math.min(offset, collection.size());
        int end = Math.min(collection.size(), start + limit);
        for (int index = start; index < end; index++) {
            page.add(collection.get(index));
        }
        result.set(collectionField, page);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("limit", limit);
        meta.put("returned", page.size());
        meta.put("hasMore", end < collection.size());
        meta.put("nextPosition", end);
        result.set("meta", meta);
        return result;
    }
}
