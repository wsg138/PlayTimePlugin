package org.enthusia.playtime.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncWriteQueueLedgerTest {
    @Test
    void enqueueDoesNotWaitForDelayedDurableRead() throws Exception {
        AsyncWriteQueue queue = new AsyncWriteQueue(null, null, new PerformanceCounters(), 20L);
        UUID player = UUID.randomUUID();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);

        queue.enqueueMinute(player, 1, 0);
        CompletableFuture<Long> effective = CompletableFuture.supplyAsync(() -> queue.getEffectiveActiveMinutes(player, () -> {
            readStarted.countDown();
            try {
                releaseRead.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return 59L;
        }));

        assertTrue(readStarted.await(1, TimeUnit.SECONDS));
        long startedAt = System.nanoTime();
        queue.enqueueMinute(player, 1, 0);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        releaseRead.countDown();

        assertTrue(elapsedMillis < 100L);
        assertEquals(61L, effective.get(1, TimeUnit.SECONDS));
    }
}
