package org.enthusia.playtime.data.model;

import java.time.Instant;
import java.util.UUID;

public record PlayerProfile(UUID uuid,
                            String username,
                            String displayName,
                            Instant seenAt) {
}
