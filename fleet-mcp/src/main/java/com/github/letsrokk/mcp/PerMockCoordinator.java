package com.github.letsrokk.mcp;

import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Singleton
public final class PerMockCoordinator {

    private final HashMap<String, LockEntry> locks = new HashMap<>();

    public <T> T serialized(String mockId, Supplier<T> action) {
        MockIdValidator.requireValid(mockId);
        LockEntry entry;
        synchronized (locks) {
            entry = locks.computeIfAbsent(mockId, ignored -> new LockEntry());
            entry.users++;
        }
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            synchronized (locks) {
                entry.users--;
                if (entry.users == 0) {
                    locks.remove(mockId, entry);
                }
            }
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
