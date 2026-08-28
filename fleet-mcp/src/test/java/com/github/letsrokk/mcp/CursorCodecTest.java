package com.github.letsrokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CursorCodec cursors = new CursorCodec(mapper);

    @Test
    void roundTripsRawPositionForCanonicalScope() throws Exception {
        var firstScope = mapper.readTree("{\"mockId\":\"orders\",\"filter\":{\"method\":\"GET\",\"url\":\"/a\"}}");
        var reorderedScope = mapper.readTree("{\"filter\":{\"url\":\"/a\",\"method\":\"GET\"},\"mockId\":\"orders\"}");

        String cursor = cursors.encode("find_requests", firstScope, 27);

        assertEquals(27, cursors.decode("find_requests", reorderedScope, cursor));
    }

    @Test
    void rejectsMalformedCrossToolAndCrossFilterCursors() throws Exception {
        var scope = mapper.readTree("{\"mockId\":\"orders\",\"filter\":{\"method\":\"GET\"}}");
        String cursor = cursors.encode("find_requests", scope, 5);

        assertThrows(IllegalArgumentException.class,
                () -> cursors.decode("find_requests", scope, "not-base64"));
        assertThrows(IllegalArgumentException.class,
                () -> cursors.decode("list_unmatched_requests", scope, cursor));
        assertThrows(IllegalArgumentException.class,
                () -> cursors.decode("find_requests",
                        mapper.readTree("{\"mockId\":\"orders\",\"filter\":{\"method\":\"POST\"}}"), cursor));
    }
}
