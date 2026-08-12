package org.enthusia.playtime.activity;

/**
 * Immutable, server-side behavioral sample used by the AFK automation detector.
 *
 * <p>The bit flags deliberately allow related Bukkit/Paper events from one physical
 * action (for example an arm swing followed by an attack event) to be merged into
 * one sample instead of double-counting the input.</p>
 */
public record BehaviorSample(long timestampMillis,
                             int actions,
                             double dx,
                             double dy,
                             double dz,
                             float yawDelta,
                             float pitchDelta,
                             boolean patternEligible) {
    public static final int MOVE = 1;
    public static final int ROTATE = 1 << 1;
    public static final int JUMP = 1 << 2;
    public static final int SWING = 1 << 3;
    public static final int ATTACK = 1 << 4;
    public static final int INTERACT = 1 << 5;
    public static final int BLOCK_BREAK = 1 << 6;
    public static final int BLOCK_PLACE = 1 << 7;
    public static final int CHAT = 1 << 8;
    public static final int COMMAND = 1 << 9;

    static final int SEMANTIC_ACTIONS = SWING | ATTACK | INTERACT | BLOCK_BREAK
            | BLOCK_PLACE | CHAT | COMMAND;

    public boolean has(int action) {
        return (actions & action) != 0;
    }

    public boolean hasMovement() {
        return has(MOVE);
    }

    public boolean hasRotation() {
        return has(ROTATE);
    }

    public double horizontalDistance() {
        return Math.hypot(dx, dz);
    }

    public double distance() {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float turnAmount() {
        return Math.abs(yawDelta) + Math.abs(pitchDelta);
    }

    public int semanticActions() {
        return actions & SEMANTIC_ACTIONS;
    }

    public BehaviorSample mergeActions(int additionalActions, long timestamp) {
        return new BehaviorSample(Math.max(timestampMillis, timestamp),
                actions | additionalActions, dx, dy, dz, yawDelta, pitchDelta,
                patternEligible);
    }
}
