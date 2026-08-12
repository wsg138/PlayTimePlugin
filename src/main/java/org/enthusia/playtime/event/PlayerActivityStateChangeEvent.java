package org.enthusia.playtime.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.enthusia.playtime.activity.ActivityState;

import java.time.Instant;
import java.util.Objects;

/**
 * Fired synchronously when the effective live activity state actually changes.
 * SUSPICIOUS means activity is not trusted as genuine; it is not a cheating verdict.
 */
public final class PlayerActivityStateChangeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final ActivityState oldState;
    private final ActivityState newState;
    private final Instant changedAt;

    public PlayerActivityStateChangeEvent(Player player,
                                          ActivityState oldState,
                                          ActivityState newState,
                                          Instant changedAt) {
        super(player);
        this.oldState = Objects.requireNonNull(oldState, "oldState");
        this.newState = Objects.requireNonNull(newState, "newState");
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
    }

    public ActivityState getOldState() {
        return oldState;
    }

    public ActivityState getNewState() {
        return newState;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
