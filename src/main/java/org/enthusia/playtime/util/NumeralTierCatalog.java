package org.enthusia.playtime.util;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Immutable, threshold-ordered playtime numeral tiers. */
public final class NumeralTierCatalog {
    private static final List<Tier> DEFAULT_TIERS = List.of(
            new Tier("I", 60, "gray"), new Tier("II", 480, "white"), new Tier("III", 1200, "green"),
            new Tier("IV", 2700, "yellow"), new Tier("V", 5400, "gold"), new Tier("VI", 10200, "light_purple"),
            new Tier("VII", 19200, "blue"), new Tier("VIII", 34800, "dark_aqua"), new Tier("IX", 65400, "red"),
            new Tier("x", 120000, "dark_red"), new Tier("y", 300000, "dark_purple"), new Tier("z", 900000, "aqua"));
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
