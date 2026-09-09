package com.github.letsrokk.mockops;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSelectionTest {
    @Test void selectsLatestPatchAndRevisionAcrossNewestMinorLines() {
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.12.9", "3.13.1", "3.13.2-1", "3.13.2-3", "3.14.1-2",
                        "3.14.1-alpine", "3.15.0-beta", "latest", "2.35.0"), 2, AllowedVersionRange.parse("[3.0,4.0)"));

        assertEquals(Map.of("3.14.1", "example/wiremock:3.14.1-2",
                "3.13.2", "example/wiremock:3.13.2-3"), selection.selectable());
        assertEquals(List.of("3.14.1", "3.13.2"), new ArrayList<>(selection.selectable().keySet()));
        assertTrue(selection.candidates().stream().anyMatch(tag -> tag.version().equals("3.12.9")),
                "the command needs candidates outside the displayed minor lines");
    }

    @Test void selectionIsDeterministicAndPreservesTheChosenNumericRevisionTag() {
        CatalogSelection.Selection forward = CatalogSelection.select("example/wiremock",
                List.of("3.14.2-2", "3.14.2-7", "3.14.1", "3.13.9"), 1, AllowedVersionRange.parse("[3.0,4.0)"));
        CatalogSelection.Selection reverse = CatalogSelection.select("example/wiremock",
                List.of("3.13.9", "3.14.1", "3.14.2-7", "3.14.2-2"), 1, AllowedVersionRange.parse("[3.0,4.0)"));

        assertEquals(Map.of("3.14.2", "example/wiremock:3.14.2-7"), forward.selectable());
        assertEquals(forward, reverse);
    }

    @Test void appliesMinorLinesExactlyAndIgnoresUnparseableNumericOverflow() {
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.10.1", "3.11.1", "3.12.1", "3.999999999999999999999.1"), 2, AllowedVersionRange.parse("[3.0,4.0)"));

        assertEquals(List.of("3.12.1", "3.11.1"), new ArrayList<>(selection.selectable().keySet()));
        assertEquals(3, selection.candidates().size());
    }
    @Test void filtersBeforeLatestPatchAndMinorLineSelection() {
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.12.9", "3.13.2-7", "3.13.3", "3.14.0", "4.0.0"), 1,
                AllowedVersionRange.parse("[3.12,3.13.2]"));
        assertEquals(Map.of("3.13.2", "example/wiremock:3.13.2-7"), selection.selectable());
        assertEquals(2, selection.candidates().size());
    }

    @Test void rangeUsesNumericVersionTuplesAndIgnoresImageRevision() {
        for (String range : List.of("[3.9,3.10]", "(3.9,3.10]", "[3.9,3.10)", "(3.9,3.10)")) {
            AllowedVersionRange interval = AllowedVersionRange.parse(range);
            assertEquals(range.startsWith("["), interval.contains(WireMockTag.parse("3.9.0-99").orElseThrow()));
            assertEquals(range.endsWith("]"), interval.contains(WireMockTag.parse("3.10.0").orElseThrow()));
            assertTrue(interval.contains(WireMockTag.parse("3.9.9").orElseThrow()));
        }
        assertTrue(AllowedVersionRange.parse("[3.13.2,3.13.2]")
                .contains(WireMockTag.parse("3.13.2-7").orElseThrow()));
        assertTrue(AllowedVersionRange.parse("[3.0,4.0)").contains(WireMockTag.parse("3.999.0").orElseThrow()));
    }

    @Test void rejectsMalformedReversedAndEmptyIntervals() {
        for (String invalid : List.of("3.x", "", "[3,4)", "[3.1,3.0]", "(3.0,3.0]", "[3.0,3.0)",
                "(3.0,3.0)", "[3.0, 4.0)", "[3.01,4.0)", "[3.0-beta,4.0)", "[3.0,)", "[,4.0)")) {
            assertThrows(IllegalArgumentException.class, () -> AllowedVersionRange.parse(invalid), invalid);
        }
    }
}
