package org.enthusia.playtime.util;

import org.bukkit.scheduler.BukkitRunnable;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.PlaytimeRepository;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AsyncWriteQueue {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

    public AsyncWriteQueue(PlayTimePlugin plugin, PlaytimeRepository repository, long flushIntervalTicks) {
        this.plugin = plugin;
        this.repository = repository;

        new BukkitRunnable() {
            @Override
            public void run() {
                drain();
            }
        }.runTaskTimerAsynchronously(plugin, flushIntervalTicks, flushIntervalTicks);
    }

    public void enqueue(Runnable task) {
        if (task != null) queue.add(task);
    }

    public void flushNow() {
        drain();
    }

    private void drain() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("Async write task failed: " + t.getMessage());
            }
        }
    }
}
