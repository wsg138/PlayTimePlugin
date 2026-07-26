package org.enthusia.playtime.util;

import org.enthusia.playtime.config.PlaytimeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps total playtime hours to configurable numeral tiers with optional colors.
 */
public final class RomanTiering {

    private static List<Tier> TIERS = new ArrayList<>();

    static {
        // Sensible defaults that are used when the plugin hasn't initialized yet
        // or numerals are disabled in config.
        List<Tier> defaults = new ArrayList<>();
        defaults.add(new Tier("I", 1, "&7"));
        defaults.add(new Tier("II", 8, "&f"));
        defaults.add(new Tier("III", 20, "&a"));
        defaults.add(new Tier("IV", 45, "&e"));
        defaults.add(new Tier("V", 90, "&6"));
        defaults.add(new Tier("VI", 170, "&d"));
        defaults.add(new Tier("VII", 320, "&9"));
        defaults.add(new Tier("VIII", 580, "&3"));
        defaults.add(new Tier("IX", 1090, "&c"));
        defaults.add(new Tier("x", 2000, "&4"));
        defaults.add(new Tier("y", 5000, "&5"));
        defaults.add(new Tier("z", 15000, "&b"));
        TIERS = Collections.unmodifiableList(defaults);
    }

    private RomanTiering() {
    }

    /**
     * Replaces the tier list from config. Call this after loading the plugin config.
     */
    public static void initializeFromConfig(PlaytimeConfig config) {
        PlaytimeConfig.Numerals numerals = config.numerals();
        if (!numerals.enabled() || numerals.tiers().isEmpty()) {
            return; // keep defaults
        }
        List<Tier> tiers = new ArrayList<>();
        for (PlaytimeConfig.NumeralTier nt : numerals.tiers()) {
            tiers.add(new Tier(nt.label(), nt.hours(), nt.color()));
        }
        TIERS = Collections.unmodifiableList(tiers);
    }

    /**
     * @return highest tier unlocked for the provided minutes, or null if none.
     */
    public static Tier getTierForMinutes(long totalMinutes) {
        long hours = totalMinutes / 60;
        Tier best = null;
        for (Tier tier : TIERS) {
            if (hours >= tier.requiredHours && (best == null || tier.requiredHours > best.requiredHours)) {
                best = tier;
            }
        }
        return best;
    }

    public static List<Tier> getTiers() {
        return TIERS;
    }

    public static final class Tier {
        private final String label;
        private final long requiredHours;
        private final String color;

        public Tier(String label, long requiredHours, String color) {
            this.label = label;
            this.requiredHours = requiredHours;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public long requiredHours() {
            return requiredHours;
        }

        public String color() {
            return color;
        }

        /**
         * Returns the color + label formatted with legacy color codes.
         */
        public String coloredLabel() {
            return color + label;
        }
    }
}
