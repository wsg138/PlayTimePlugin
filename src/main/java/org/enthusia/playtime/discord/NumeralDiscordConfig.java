package org.enthusia.playtime.discord;

import org.bukkit.configuration.ConfigurationSection;
import org.enthusia.playtime.util.NumeralTierCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public record NumeralDiscordConfig(NumeralRolePolicy policy) {
    public static Optional<NumeralDiscordConfig> load(ConfigurationSection config, NumeralTierCatalog catalog) {
        String base = "numerals.discord-roles";
        if (!config.getBoolean(base + ".enabled", false)) return Optional.empty();
        String configuredMode = config.getString(base + ".mode", "highest-only").toLowerCase(Locale.ROOT);
        if (!configuredMode.equals("highest-only")) {
            throw new IllegalArgumentException("Numeral Discord roles require highest-only mode: " + configuredMode);
        }
        ConfigurationSection roleSection = config.getConfigurationSection(base + ".role-ids");
        Map<String, Object> configuredIds = roleSection == null ? Map.of() : roleSection.getValues(false);
        Map<String, String> ids = catalog.tiers().stream().collect(Collectors.toUnmodifiableMap(
                NumeralTierCatalog.Tier::label,
                tier -> {
                    Object configured = configuredIds.get(tier.label());
                    if (configured == null && roleSection != null) configured = roleSection.getString(tier.label());
                    return configured instanceof String id ? id.trim() : "";
                }));
        return Optional.of(new NumeralDiscordConfig(new NumeralRolePolicy(catalog, ids)));
    }
}
