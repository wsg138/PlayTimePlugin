package org.enthusia.playtime.activity;

import org.bukkit.Location;

public final class ActivitySample {
    public final long lastMovementMillis;
    public final long lastChatMillis;
    public final long lastCommandMillis;
    public final long lastAnyActivityMillis;
    public final Location lastLocation;
    public final float lastYaw;
    public final float lastPitch;

    public ActivitySample(long lastMovementMillis,
                          long lastChatMillis,
                          long lastCommandMillis,
                          long lastAnyActivityMillis,
                          Location lastLocation,
                          float lastYaw,
                          float lastPitch) {
        this.lastMovementMillis = lastMovementMillis;
        this.lastChatMillis = lastChatMillis;
        this.lastCommandMillis = lastCommandMillis;
        this.lastAnyActivityMillis = lastAnyActivityMillis;
        this.lastLocation = lastLocation;
        this.lastYaw = lastYaw;
        this.lastPitch = lastPitch;
    }

    public ActivitySample withMovement(long now, Location loc) {
        return new ActivitySample(now, lastChatMillis, lastCommandMillis,
                now, loc, loc.getYaw(), loc.getPitch());
    }

    public ActivitySample withChat(long now) {
        return new ActivitySample(lastMovementMillis, now, lastCommandMillis,
                now, lastLocation, lastYaw, lastPitch);
    }

    public ActivitySample withCommand(long now) {
        return new ActivitySample(lastMovementMillis, lastChatMillis, now,
                now, lastLocation, lastYaw, lastPitch);
    }
}
