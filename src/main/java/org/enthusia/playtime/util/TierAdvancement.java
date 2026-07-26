package org.enthusia.playtime.util;

import java.util.Optional;

/** Determines a tier crossing from the accepted active-minute increment only. */
public final class TierAdvancement {
    private TierAdvancement() {
    }

    public static Optional<NumeralTierCatalog.Tier> reachedTier(NumeralTierCatalog catalog, long beforeActiveMinutes,
                                                                 long acceptedActiveMinutes) {
        if (acceptedActiveMinutes <= 0) {
            return Optional.empty();
        }
        NumeralTierCatalog.Tier oldTier = catalog.tierForMinutes(beforeActiveMinutes).orElse(null);
        NumeralTierCatalog.Tier newTier = catalog.tierForMinutes(beforeActiveMinutes + acceptedActiveMinutes).orElse(null);
        return newTier == null || newTier == oldTier ? Optional.empty() : Optional.of(newTier);
    }
}
