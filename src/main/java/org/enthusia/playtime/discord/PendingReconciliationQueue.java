package org.enthusia.playtime.discord;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Versioned pending-work state so an older completion cannot erase a newer request for the same key. */
final class PendingReconciliationQueue<K> {
    static final long IMMEDIATE = Long.MIN_VALUE;

    private final Map<K, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    Pending request(K key) {
        Objects.requireNonNull(key, "key");
        Pending request = new Pending(IMMEDIATE, sequence.incrementAndGet());
        pending.put(key, request);
        return request;
    }

    Map<K, Pending> snapshot() {
        return Map.copyOf(pending);
    }

    Set<K> keys() {
        return Set.copyOf(pending.keySet());
    }

    boolean complete(K key, Pending request) {
        return pending.remove(key, request);
    }

    boolean retry(K key, Pending request, long dueNanos) {
        return pending.replace(key, request, new Pending(dueNanos, request.version()));
    }

    record Pending(long dueNanos, long version) { }
}
