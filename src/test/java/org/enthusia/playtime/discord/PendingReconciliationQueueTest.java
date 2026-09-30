package org.enthusia.playtime.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingReconciliationQueueTest {
    @Test
    void newerRequestSurvivesOlderSuccessfulCompletion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending older = queue.request("account");
        PendingReconciliationQueue.Pending newer = queue.request("account");

        assertFalse(queue.complete("account", older));
        assertEquals(newer, queue.snapshot().get("account"));
    }

    @Test
    void newerRequestSurvivesOlderRetryCompletion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending older = queue.request("account");
        PendingReconciliationQueue.Pending newer = queue.request("account");

        assertFalse(queue.retry("account", older, 123L));
        assertEquals(newer, queue.snapshot().get("account"));
    }

    @Test
    void currentFailureIsRetriedWithoutChangingItsVersion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending current = queue.request("account");

        assertTrue(queue.retry("account", current, 123L));
        PendingReconciliationQueue.Pending retry = queue.snapshot().get("account");
        assertEquals(123L, retry.dueNanos());
        assertEquals(current.version(), retry.version());
    }
}
