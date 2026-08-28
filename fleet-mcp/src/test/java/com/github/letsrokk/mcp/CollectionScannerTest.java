package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CollectionScannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void scansNamedArrayFromRawCursorAndRetainsOnlyLimitItems() throws Exception {
        byte[] json = "{\"requests\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4}],\"meta\":{}}"
                .getBytes(StandardCharsets.UTF_8);

        CollectionScan page = CollectionScanner.scan(mapper, json, "requests", 1, 2, 1024, 100);

        assertEquals(mapper.readTree("[{\"id\":2},{\"id\":3}]"), page.items());
        assertTrue(page.hasMore());
        assertEquals(3, page.nextPosition());
    }

    @Test
    void scansRootArrayAndReturnsFinalPage() throws Exception {
        CollectionScan page = CollectionScanner.scan(mapper, "[1,2,3]".getBytes(StandardCharsets.UTF_8),
                "items", 2, 5, 1024, 100);

        assertEquals(mapper.readTree("[3]"), page.items());
        assertFalse(page.hasMore());
        assertEquals(3, page.nextPosition());
    }

    @Test
    void enforcesByteAndItemScanBudgets() {
        byte[] json = "[1,2,3,4]".getBytes(StandardCharsets.UTF_8);

        assertEquals("RESULT_TOO_LARGE", assertThrows(McpOperationException.class,
                () -> CollectionScanner.scan(mapper, json, "items", 0, 4, 4, 100)).code());
        assertEquals("RESULT_TOO_LARGE", assertThrows(McpOperationException.class,
                () -> CollectionScanner.scan(mapper, json, "items", 0, 4, 1024, 2)).code());
    }
}
