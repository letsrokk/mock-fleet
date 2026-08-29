package com.github.letsrokk;

final class TraversalBudget {

    private final int maxDepth;
    private final int maxEntries;
    private int entryCount;

    TraversalBudget(int maxDepth, int maxEntries) {
        this.maxDepth = maxDepth;
        this.maxEntries = maxEntries;
    }

    void visit(int relativeDepth) {
        if (relativeDepth > maxDepth) {
            throw new LimitExceeded("maxDepth", maxDepth);
        }
        if (entryCount >= maxEntries) {
            throw new LimitExceeded("maxEntries", maxEntries);
        }
        entryCount++;
    }

    static final class LimitExceeded extends RuntimeException {

        private final String limit;
        private final int maximum;

        LimitExceeded(String limit, int maximum) {
            this.limit = limit;
            this.maximum = maximum;
        }

        String limit() {
            return limit;
        }

        int maximum() {
            return maximum;
        }
    }
}
