package com.github.letsrokk.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.util.Map;

public final class CollectionScanner {

    private CollectionScanner() {
    }

    public static CollectionScan scan(ObjectMapper mapper, byte[] json, String collectionField, long position,
            int limit, long maxBytes, int maxItems) {
        if (position < 0 || limit < 1 || maxBytes < 1 || maxItems < 1) {
            throw new IllegalArgumentException("Collection scan limits must be positive");
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.START_OBJECT) {
                token = findNamedArray(parser, collectionField);
            }
            if (token != JsonToken.START_ARRAY) {
                throw new McpOperationException("INVALID_UPSTREAM_RESPONSE",
                        "Upstream response is missing collection field " + collectionField, false,
                        Map.of("field", collectionField));
            }

            ArrayNode items = mapper.createArrayNode();
            long rawPosition = 0;
            long nextPosition = position;
            int scannedItems = 0;
            boolean hasMore = false;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode item = mapper.readTree(parser);
                scannedItems++;
                long scannedBytes = Math.max(0, parser.currentLocation().getByteOffset());
                enforceBudgets(scannedBytes, scannedItems, maxBytes, maxItems, rawPosition);
                if (rawPosition++ < position) {
                    continue;
                }
                if (items.size() == limit) {
                    hasMore = true;
                    nextPosition = rawPosition - 1;
                    break;
                }
                items.add(item);
                nextPosition = rawPosition;
            }
            return new CollectionScan(items, nextPosition, hasMore,
                    Math.max(0, parser.currentLocation().getByteOffset()), scannedItems);
        } catch (McpOperationException | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new McpOperationException("INVALID_UPSTREAM_RESPONSE", "Upstream collection JSON is malformed",
                    false, Map.of("field", collectionField));
        }
    }

    private static JsonToken findNamedArray(JsonParser parser, String field) throws IOException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                continue;
            }
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field.equals(name)) {
                return value;
            }
            parser.skipChildren();
        }
        return null;
    }

    private static void enforceBudgets(long bytes, int items, long maxBytes, int maxItems, long position) {
        if (bytes > maxBytes) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Collection scan byte limit exceeded", false,
                    Map.of("limitBytes", maxBytes, "position", position));
        }
        if (items > maxItems) {
            throw new McpOperationException("RESULT_TOO_LARGE", "Collection scan item limit exceeded", false,
                    Map.of("limitItems", maxItems, "position", position));
        }
    }
}
