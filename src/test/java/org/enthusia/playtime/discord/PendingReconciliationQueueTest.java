package org.enthusia.playtime.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingReconciliationQueueTest {
    private static final String ACCOUNT = "account";
    @Test
    void newerRequestSurvivesOlderSuccessfulCompletion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending older = queue.request(ACCOUNT);
        PendingReconciliationQueue.Pending newer = queue.request(ACCOUNT);

        assertFalse(queue.complete(ACCOUNT, older));
        assertEquals(newer, queue.snapshot().get(ACCOUNT));
    }

    @Test
    void newerRequestSurvivesOlderRetryCompletion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending older = queue.request(ACCOUNT);
        PendingReconciliationQueue.Pending newer = queue.request(ACCOUNT);

        assertFalse(queue.retry(ACCOUNT, older, 123L));
        assertEquals(newer, queue.snapshot().get(ACCOUNT));
    }

    @Test
    void currentFailureIsRetriedWithoutChangingItsVersion() {
        PendingReconciliationQueue<String> queue = new PendingReconciliationQueue<>();
        PendingReconciliationQueue.Pending current = queue.request(ACCOUNT);

        assertTrue(queue.retry(ACCOUNT, current, 123L));
        PendingReconciliationQueue.Pending retry = queue.snapshot().get(ACCOUNT);
        assertEquals(123L, retry.dueNanos());
        assertEquals(current.version(), retry.version());
    }
}
