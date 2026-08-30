package com.github.letsrokk.updater;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSelectionTest {
    @Test void selectsLatestPatchAndRevisionAcrossNewestMinorLines() {
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.12.9", "3.13.1", "3.13.2-1", "3.13.2-3", "3.14.1-2",
                        "3.14.1-alpine", "3.15.0-beta", "latest", "2.35.0"), 2);

        assertEquals(Map.of("3.14.1", "example/wiremock:3.14.1-2",
                "3.13.2", "example/wiremock:3.13.2-3"), selection.selectable());
        assertEquals(List.of("3.14.1", "3.13.2"), new ArrayList<>(selection.selectable().keySet()));
        assertTrue(selection.candidates().stream().anyMatch(tag -> tag.version().equals("3.12.9")),
                "the command needs candidates outside the displayed minor lines");
    }

    @Test void selectionIsDeterministicAndPreservesTheChosenNumericRevisionTag() {
        CatalogSelection.Selection forward = CatalogSelection.select("example/wiremock",
                List.of("3.14.2-2", "3.14.2-7", "3.14.1", "3.13.9"), 1);
        CatalogSelection.Selection reverse = CatalogSelection.select("example/wiremock",
                List.of("3.13.9", "3.14.1", "3.14.2-7", "3.14.2-2"), 1);

        assertEquals(Map.of("3.14.2", "example/wiremock:3.14.2-7"), forward.selectable());
        assertEquals(forward, reverse);
    }

    @Test void appliesMinorLinesExactlyAndIgnoresUnparseableNumericOverflow() {
        CatalogSelection.Selection selection = CatalogSelection.select("example/wiremock",
                List.of("3.10.1", "3.11.1", "3.12.1", "3.999999999999999999999.1"), 2);

        assertEquals(List.of("3.12.1", "3.11.1"), new ArrayList<>(selection.selectable().keySet()));
        assertEquals(3, selection.candidates().size());
    }
    @Test void validatesDefaultConstraintsWithoutFilteringSelectableVersions() {
        assertTrue(CatalogSelection.matchesConstraint("3.x", "3.14.1"));
        assertTrue(CatalogSelection.matchesConstraint("3.13.x", "3.13.2"));
        assertFalse(CatalogSelection.matchesConstraint("3.13.x", "3.14.1"));
        assertThrows(IllegalArgumentException.class, () -> CatalogSelection.matchesConstraint("3.13", "3.13.2"));
    }
}
