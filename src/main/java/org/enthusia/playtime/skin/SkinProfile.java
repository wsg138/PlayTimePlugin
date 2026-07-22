package org.enthusia.playtime.skin;

import java.time.Instant;
import java.util.UUID;

public record SkinProfile(
        UUID uuid,
        String textureValue,
        String textureSignature,
        String lastKnownName,
        Instant updatedAt
) {
}
