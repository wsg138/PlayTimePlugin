package org.enthusia.playtime.command;

import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PlaytimeLookupTest {
    @Test void coldLookupCompletesWithoutASecondCommand() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        AtomicBoolean loading = new AtomicBoolean(true);
        AtomicReference<Optional<PlaytimeSnapshot>> value = new AtomicReference<>(Optional.empty());
        AtomicReference<PlaytimeLookup.Result> result = new AtomicReference<>();
        PlaytimeLookup.start(value::get, loading::get, () -> false, scheduled::add, () -> true, result::set, 5);
        assertNull(result.get());
        value.set(Optional.of(new PlaytimeSnapshot(12, 3, 15)));
        loading.set(false);
        scheduled.remove().run();
        assertEquals(15, result.get().snapshot().totalMinutes);
        assertEquals(PlaytimeLookup.Status.FOUND, result.get().status());
    }
    @Test void timesOutAndStopsWhenContextIsGone() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        AtomicReference<PlaytimeLookup.Result> result = new AtomicReference<>();
        PlaytimeLookup.start(Optional::empty, () -> true, () -> false, scheduled::add, () -> true, result::set, 1);
        scheduled.remove().run();
        assertEquals(PlaytimeLookup.Status.TIMEOUT, result.get().status());
        PlaytimeLookup.start(Optional::empty, () -> true, () -> false, scheduled::add, () -> false, result::set, 5);
        assertTrue(scheduled.isEmpty());
    }
    @Test void successfulEmptyReadReportsNoData() {
        AtomicReference<PlaytimeLookup.Result> result = new AtomicReference<>();
        PlaytimeLookup.start(Optional::empty, () -> false, () -> false, r -> fail("unexpected retry"), () -> true, result::set, 5);
        assertEquals(PlaytimeLookup.Status.NOT_FOUND, result.get().status());
    }
    @Test void failedReadDoesNotReportMissingPlayerOrRetryForever() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        AtomicBoolean loading = new AtomicBoolean(true);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<PlaytimeLookup.Result> result = new AtomicReference<>();
        PlaytimeLookup.start(Optional::empty, loading::get, failed::get, scheduled::add, () -> true, result::set, 5);
        failed.set(true);
        loading.set(false);
        scheduled.remove().run();
        assertEquals(PlaytimeLookup.Status.FAILED, result.get().status());
        assertTrue(scheduled.isEmpty());
    }
}

