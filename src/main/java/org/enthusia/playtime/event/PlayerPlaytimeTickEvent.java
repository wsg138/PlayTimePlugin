package org.enthusia.playtime.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.enthusia.playtime.activity.ActivityState;

/**
 * Fired once per minute for each online player.
 * Other plugins (or our own listeners) can cancel or modify
 * how many minutes are credited for this tick.
 */
public final class PlayerPlaytimeTickEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ActivityState state;
    private int activeMinutes;
    private int afkMinutes;
    private boolean cancelled;

    /**
     * Synchronous event (called from a BukkitRunnable on the main thread).
     *
     * @param player        player this tick is about
     * @param state         ACTIVE / IDLE / AFK / SUSPICIOUS
     * @param activeMinutes minutes of ACTIVE time to credit (usually 0 or 1)
     * @param afkMinutes    minutes of AFK time to credit (usually 0 or 1)
     */
    public PlayerPlaytimeTickEvent(Player player,
                                   ActivityState state,
                                   int activeMinutes,
                                   int afkMinutes) {
        super(); // synchronous event
        this.player = player;
        this.state = state;
        this.activeMinutes = activeMinutes;
        this.afkMinutes = afkMinutes;
    }

    public Player getPlayer() {
        return player;
    }

    public ActivityState getState() {
        return state;
    }

    public int getActiveMinutes() {
        return activeMinutes;
    }

    public void setActiveMinutes(int activeMinutes) {
        this.activeMinutes = activeMinutes;
    }

    public int getAfkMinutes() {
        return afkMinutes;
    }

    public void setAfkMinutes(int afkMinutes) {
        this.afkMinutes = afkMinutes;
    }

    // ---- Cancellable ----

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    // ---- Handler list ----

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
