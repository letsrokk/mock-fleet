package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPaginatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void paginatesArrayResponsesWithStableMetadata() throws Exception {
        var result = JsonPaginator.page(mapper, mapper.readTree("[1,2,3,4]"), "items", 2, 1);

        assertEquals(mapper.readTree("[2,3]"), result.path("items"));
        assertEquals(4, result.path("meta").path("total").asInt());
        assertEquals(2, result.path("meta").path("limit").asInt());
        assertEquals(1, result.path("meta").path("offset").asInt());
    }

    @Test
    void preservesNonCollectionFieldsFromObjectResponses() throws Exception {
        var result = JsonPaginator.page(mapper,
                mapper.readTree("{\"requests\":[1,2,3],\"requestJournalDisabled\":false}"),
                "requests", 2, 2);

        assertEquals(mapper.readTree("[3]"), result.path("requests"));
        assertEquals(false, result.path("requestJournalDisabled").asBoolean());
    }
}
