package org.enthusia.playtime.discord;

import org.bukkit.configuration.ConfigurationSection;
import org.enthusia.playtime.util.NumeralTierCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
                tier -> roleIdFor(configuredIds, roleSection, tier.label())));
        return Optional.of(new NumeralDiscordConfig(new NumeralRolePolicy(catalog, ids)));
    }

    private static String roleIdFor(Map<String, Object> configuredIds, ConfigurationSection roleSection, String label) {
        Object configured = null;
        boolean found = false;
        for (Map.Entry<String, Object> entry : configuredIds.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(label)) continue;
            if (found && !Objects.equals(configured, entry.getValue())) {
                throw new IllegalArgumentException("Conflicting Discord role IDs for tier " + label);
            }
            configured = entry.getValue();
            found = true;
        }
        if (!found && roleSection != null) configured = roleSection.getString(label);
        return configured instanceof String id ? id.trim() : "";
    }
}
