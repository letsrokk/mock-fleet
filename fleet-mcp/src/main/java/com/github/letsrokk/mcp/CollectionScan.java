package com.github.letsrokk.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;

public record CollectionScan(ArrayNode items, long nextPosition, boolean hasMore, long scannedBytes, int scannedItems) {
}
