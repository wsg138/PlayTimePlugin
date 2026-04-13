package org.enthusia.playtime.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps total playtime hours to simple roman-style tiers.
 */
public final class RomanTiering {

    private static final List<Tier> TIERS;

    static {
        List<Tier> tiers = new ArrayList<>();
        tiers.add(new Tier("I", 1));
        tiers.add(new Tier("II", 8));
        tiers.add(new Tier("III", 20));
        tiers.add(new Tier("IV", 45));
        tiers.add(new Tier("V", 90));
        tiers.add(new Tier("VI", 170));
        tiers.add(new Tier("VII", 320));
        tiers.add(new Tier("VIII", 580));
        tiers.add(new Tier("IX", 1090));
        tiers.add(new Tier("x", 2000));
        tiers.add(new Tier("y", 5000));
        tiers.add(new Tier("z", 15000));
        TIERS = Collections.unmodifiableList(tiers);
    }

    private RomanTiering() {
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

        public Tier(String label, long requiredHours) {
            this.label = label;
            this.requiredHours = requiredHours;
        }

        public String label() {
            return label;
        }

        public long requiredHours() {
            return requiredHours;
        }
    }
}
