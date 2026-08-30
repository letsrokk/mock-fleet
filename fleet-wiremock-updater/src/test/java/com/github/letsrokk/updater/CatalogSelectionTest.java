package com.github.letsrokk.updater;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSelectionTest {
    @Test void selectsLatestPatchAndRevisionAcrossNewestMinorLines() {
        assertEquals(Map.of("3.14.1", "example/wiremock:3.14.1-2", "3.13.2", "example/wiremock:3.13.2-3"),
                CatalogSelection.select("example/wiremock", List.of("3.13.1", "3.13.2-1", "3.13.2-3", "3.14.1-2", "3.14.1-alpine", "3.15.0-beta", "latest", "2.35.0"), 2));
    }
    @Test void validatesDefaultConstraintsWithoutFilteringSelectableVersions() {
        assertTrue(CatalogSelection.matchesConstraint("3.x", "3.14.1"));
        assertTrue(CatalogSelection.matchesConstraint("3.13.x", "3.13.2"));
        assertFalse(CatalogSelection.matchesConstraint("3.13.x", "3.14.1"));
        assertThrows(IllegalArgumentException.class, () -> CatalogSelection.matchesConstraint("3.13", "3.13.2"));
    }
}
