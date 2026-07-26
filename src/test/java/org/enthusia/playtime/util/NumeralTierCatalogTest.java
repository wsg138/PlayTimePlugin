package org.enthusia.playtime.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumeralTierCatalogTest {
    private final NumeralTierCatalog catalog = new NumeralTierCatalog(List.of(
            new NumeralTierCatalog.Tier("III", 120, "&b"),
            new NumeralTierCatalog.Tier("I", 60, "&7"),
            new NumeralTierCatalog.Tier("II", 90, "&a")));

    @Test
    void sortsConfiguredTiersByThresholdRatherThanSourceOrder() {
        assertEquals(List.of("I", "II", "III"), catalog.tiers().stream().map(NumeralTierCatalog.Tier::label).toList());
    }

    @Test
    void findsNoTierBeforeFirstBoundaryAndPreservesBoundaryBehavior() {
        assertTrue(catalog.tierForMinutes(59).isEmpty());
        assertEquals("I", catalog.tierForMinutes(60).orElseThrow().label());
        assertEquals("II", catalog.tierForMinutes(119).orElseThrow().label());
        assertEquals("III", catalog.tierForMinutes(120).orElseThrow().label());
    }

    @Test
    void detectsFirstAndLaterTierCrossingsWithoutSameTierAnnouncements() {
        assertEquals("I", TierAdvancement.reachedTier(catalog, 59, 1).orElseThrow().label());
        assertEquals("II", TierAdvancement.reachedTier(catalog, 89, 2).orElseThrow().label());
        assertTrue(TierAdvancement.reachedTier(catalog, 60, 1).isEmpty());
        assertTrue(TierAdvancement.reachedTier(catalog, 0, 0).isEmpty());
    }

    @Test
    void multiMinuteIncrementSelectsFinalTier() {
        assertEquals("III", TierAdvancement.reachedTier(catalog, 59, 70).orElseThrow().label());
    }
}
