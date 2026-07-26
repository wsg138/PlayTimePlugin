package org.enthusia.playtime.util;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Immutable, threshold-ordered playtime numeral tiers. */
public final class NumeralTierCatalog {
    private static final List<Tier> DEFAULT_TIERS = List.of(
            new Tier("I", 60, "&7"), new Tier("II", 480, "&7"), new Tier("III", 1200, "&7"),
            new Tier("IV", 2700, "&7"), new Tier("V", 5400, "&7"), new Tier("VI", 10200, "&7"),
            new Tier("VII", 19200, "&7"), new Tier("VIII", 34800, "&7"), new Tier("IX", 65400, "&7"),
            new Tier("x", 120000, "&7"), new Tier("y", 300000, "&7"), new Tier("z", 900000, "&7"));
    private final List<Tier> tiers;

    public NumeralTierCatalog(List<Tier> tiers) {
        this.tiers = tiers.stream().sorted(Comparator.comparingLong(Tier::thresholdMinutes)).toList();
    }

    public Optional<Tier> tierForMinutes(long activeMinutes) {
        Tier result = null;
        for (Tier tier : tiers) {
            if (activeMinutes < tier.thresholdMinutes()) {
                break;
            }
            result = tier;
        }
        return Optional.ofNullable(result);
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public static List<Tier> defaultTiers() {
        return DEFAULT_TIERS;
    }

    public record Tier(String label, long thresholdMinutes, String color) {
        public long requiredHours() {
            return thresholdMinutes / 60L;
        }
    }
}
