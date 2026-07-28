package org.enthusia.playtime.util;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TierColorFormatterTest {

    @Test
    void supportsNamedHexAndLegacyTierColors() {
        assertEquals(ChatColor.GRAY + "I", TierColorFormatter.apply("gray", "I"));
        assertEquals(net.md_5.bungee.api.ChatColor.of("#12AB34") + "II",
                TierColorFormatter.apply("#12AB34", "II"));
        assertEquals(ChatColor.GREEN + "III", TierColorFormatter.apply("&a", "III"));
    }

    @Test
    void appliesTwoAndMultiStopGradientsAcrossTheWholeLabel() {
        String twoStop = TierColorFormatter.apply("gradient:#FF0000:#0000FF", "ABC");
        assertEquals("ABC", ChatColor.stripColor(twoStop));
        assertTrue(twoStop.startsWith(net.md_5.bungee.api.ChatColor.of("#FF0000").toString()));
        assertTrue(twoStop.contains(net.md_5.bungee.api.ChatColor.of("#800080").toString()));
        assertTrue(twoStop.endsWith(net.md_5.bungee.api.ChatColor.of("#0000FF") + "C"));

        String multiStop = TierColorFormatter.apply("<gradient:#FF0000:#00FF00:#0000FF>", "ABC");
        assertTrue(multiStop.contains(net.md_5.bungee.api.ChatColor.of("#00FF00").toString()));
    }

    @Test
    void existingTierTemplateTokensRenderGradientLabels() {
        NumeralTierCatalog.Tier tier =
                new NumeralTierCatalog.Tier("VII", 60, "gradient:#FF0000:#0000FF");
        String rendered = TierColorFormatter.replaceTierLabelTokens(
                "&7Unlocked %tier_color%%tier_label% at %tier_hours%h", tier);

        assertEquals("&7Unlocked VII at %tier_hours%h", ChatColor.stripColor(rendered));
    }
}
