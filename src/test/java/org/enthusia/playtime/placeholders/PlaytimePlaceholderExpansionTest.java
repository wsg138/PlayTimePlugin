package org.enthusia.playtime.placeholders;

import org.bukkit.ChatColor;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaytimePlaceholderExpansionTest {

    @Test
    void rendersNamedHexGradientLegacyAndExistingMiniMessageGradientTiers() {
        assertRomanMm("gray", "I", "<gray>I</gray>");
        assertRomanMm("#55FFAA", "IV", "<#55ffaa>IV</#55ffaa>");
        assertRomanMm("gradient:#FF5F6D:#FFC371", "VIII",
                "<gradient:#ff5f6d:#ffc371>VIII</gradient>");
        assertRomanMm("&a", "V", "<green>V</green>");
        assertRomanMm("<gradient:#ff5f6d:#ffc371>", "VIII",
                "<gradient:#ff5f6d:#ffc371>VIII</gradient>");
    }

    @Test
    void returnsEmptyForDisabledMissingOrLoadingNumeralData() {
        PlaytimeConfig.Numerals disabled = numerals(false, "gray", "I", 60);
        assertEquals("", resolveRoman(disabled, Optional.of(snapshot(60)), false, "roman_mm"));

        PlaytimeConfig.Numerals noMatch = numerals(true, "gray", "I", 60);
        assertEquals("", resolveRoman(noMatch, Optional.of(snapshot(59)), false, "roman_mm"));

        PlaytimeConfig.Numerals loading = numerals(true, "gray", "I", 60);
        assertEquals("", resolveRoman(loading, Optional.empty(), true, "roman_mm"));
        assertEquals("", resolveRoman(loading, Optional.of(snapshot(60)), true, "roman_mm"));
    }

    @Test
    void preservesPlainAndLegacyColoredPlaceholderContracts() {
        PlaytimeConfig.Numerals numerals = numerals(true, "&a", "V", 60);

        assertEquals("V", resolveRoman(numerals, Optional.of(snapshot(60)), false, "roman"));
        assertEquals(ChatColor.GREEN + "V",
                resolveRoman(numerals, Optional.of(snapshot(60)), false, "roman_colored"));
    }

    @Test
    void activePlaceholderIsAlwaysNumericForVelocitabSorting() {
        String loading = resolveMetric(Optional.empty(), true, "active");
        String loaded = resolveMetric(Optional.of(snapshot(62)), false, "active");
        String missing = resolveMetric(Optional.empty(), false, "active");

        assertEquals("0", loading);
        assertEquals("3720", loaded);
        assertEquals("0", missing);
        assertTrue(loading.matches("\\d+"));
        assertTrue(loaded.matches("\\d+"));
        assertTrue(missing.matches("\\d+"));
    }

    @Test
    void otherLifetimeLoadingFallbacksRemainUnchanged() {
        assertEquals("", resolveMetric(Optional.empty(), true, "total"));
        assertEquals("", resolveMetric(Optional.empty(), true, "active_formatted"));
        assertEquals("", resolveMetric(Optional.empty(), true, "afk"));
    }

    private void assertRomanMm(String color, String label, String expected) {
        PlaytimeConfig.Numerals numerals = numerals(true, color, label, 60);
        assertEquals(expected, resolveRoman(numerals, Optional.of(snapshot(60)), false, "roman_mm"));
    }

    private PlaytimeConfig.Numerals numerals(boolean enabled, String color, String label, long thresholdMinutes) {
        NumeralTierCatalog catalog =
                new NumeralTierCatalog(List.of(new NumeralTierCatalog.Tier(label, thresholdMinutes, color)));
        return new PlaytimeConfig.Numerals(enabled, catalog, null, null);
    }

    private PlaytimeSnapshot snapshot(long activeMinutes) {
        return new PlaytimeSnapshot(activeMinutes, activeMinutes, 0);
    }

    private String resolveRoman(
            PlaytimeConfig.Numerals numerals,
            Optional<PlaytimeSnapshot> snapshot,
            boolean loading,
            String identifier
    ) {
        return PlaytimePlaceholderExpansion.resolveRomanPlaceholder(numerals, snapshot, loading, identifier);
    }

    private String resolveMetric(
            Optional<PlaytimeSnapshot> snapshot,
            boolean loading,
            String identifier
    ) {
        return PlaytimePlaceholderExpansion.resolveLifetimeMetricPlaceholder(snapshot, loading, identifier);
    }
}
