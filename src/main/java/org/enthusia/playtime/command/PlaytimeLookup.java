package org.enthusia.playtime.command;

import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Waits for a cold display cache without blocking the server thread. */
final class PlaytimeLookup {
    enum Status { FOUND, NOT_FOUND, TIMEOUT, FAILED }
    record Result(Status status, PlaytimeSnapshot snapshot) { }

    static void start(Supplier<Optional<PlaytimeSnapshot>> read, BooleanSupplier loading, BooleanSupplier failed,
                      Consumer<Runnable> schedule, BooleanSupplier valid, Consumer<Result> complete, int retries) {
        if (!valid.getAsBoolean()) return;
        Runnable retry = () -> retryOrTimeout(schedule, complete, retries,
                () -> start(read, loading, failed, schedule, valid, complete, retries - 1));
        read.get();
        if (loading.getAsBoolean()) {
            retry.run();
            return;
        }
        // The asynchronous read may have completed between the first read and the loading check.
        if (failed.getAsBoolean()) { complete.accept(new Result(Status.FAILED, null)); return; }
        Optional<PlaytimeSnapshot> value = read.get();
        if (loading.getAsBoolean()) {
            retry.run();
        } else if (failed.getAsBoolean()) {
            complete.accept(new Result(Status.FAILED, null));
        } else if (value.isPresent()) {
            complete.accept(new Result(Status.FOUND, value.get()));
        } else {
            complete.accept(new Result(Status.NOT_FOUND, null));
        }
    }

    private static void retryOrTimeout(Consumer<Runnable> schedule, Consumer<Result> complete,
                                       int retries, Runnable next) {
        if (retries <= 0) complete.accept(new Result(Status.TIMEOUT, null));
        else schedule.accept(next);
    }
}
