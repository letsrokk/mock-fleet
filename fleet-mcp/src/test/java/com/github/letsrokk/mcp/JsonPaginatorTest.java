package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPaginatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void paginatesArrayResponsesWithCursorMetadata() throws Exception {
        var result = JsonPaginator.page(mapper, mapper.readTree("[1,2,3,4]"), "items", 2, 1);

        assertEquals(mapper.readTree("[2,3]"), result.path("items"));
        assertEquals(2, result.path("meta").path("limit").asInt());
        assertEquals(2, result.path("meta").path("returned").asInt());
        assertEquals(true, result.path("meta").path("hasMore").asBoolean());
        assertEquals(3, result.path("meta").path("nextPosition").asLong());
        assertEquals(false, result.path("meta").has("total"));
        assertEquals(false, result.path("meta").has("offset"));
    }

    @Test
    void preservesNonCollectionFieldsFromObjectResponses() throws Exception {
        var result = JsonPaginator.page(mapper,
                mapper.readTree("{\"requests\":[1,2,3],\"requestJournalDisabled\":false}"),
                "requests", 2, 2);

        assertEquals(mapper.readTree("[3]"), result.path("requests"));
        assertEquals(false, result.path("requestJournalDisabled").asBoolean());
        assertEquals(false, result.path("meta").path("hasMore").asBoolean());
        assertEquals(3, result.path("meta").path("nextPosition").asLong());
    }
}
