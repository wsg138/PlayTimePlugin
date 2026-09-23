package org.enthusia.playtime.discord;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.playtime.util.NumeralTierCatalog;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NumeralDiscordConfigTest {
    @Test void disabledByDefaultAndRejectsIncompleteEnablement() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertTrue(NumeralDiscordConfig.load(yaml, new NumeralTierCatalog(NumeralTierCatalog.defaultTiers())).isEmpty());
        yaml.set("numerals.discord-roles.enabled", true);
        assertThrows(IllegalArgumentException.class,
                () -> NumeralDiscordConfig.load(yaml, new NumeralTierCatalog(NumeralTierCatalog.defaultTiers())));
    }

    @Test void onlyHighestEarnedModeIsAccepted() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("numerals.discord-roles.enabled", true);
        yaml.set("numerals.discord-roles.mode", "highest-only");
        yaml.set("numerals.discord-roles.role-ids.I", "101");
        NumeralTierCatalog catalog = new NumeralTierCatalog(java.util.List.of(new NumeralTierCatalog.Tier("I", 60, "gray")));
        assertEquals(Set.of("101"), NumeralDiscordConfig.load(yaml, catalog).orElseThrow().policy().desiredRoles(60));
        yaml.set("numerals.discord-roles.mode", "cumulative");
        assertThrows(IllegalArgumentException.class, () -> NumeralDiscordConfig.load(yaml, catalog));
    }

    @Test void suppliedRoleIdsFollowTierOrder() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        String[] ids = {
                "1552382278712168448", "1552382306587775068", "1552382344386584576",
                "1552382374417801298", "1552382425005433042", "1552382498611404921",
                "1552382530622201896", "1552382560489705472", "1552390114812891226",
                "1552390142428455063", "1552390184027299850", "1552390213500928122"
        };
        List<NumeralTierCatalog.Tier> tiers = NumeralTierCatalog.defaultTiers();
        for (int index = 0; index < tiers.size(); index++) {
            assertEquals(ids[index], yaml.getString("numerals.discord-roles.role-ids." + tiers.get(index).label()));
        }
        yaml.set("numerals.discord-roles.enabled", true);
        assertEquals(ids[0], NumeralDiscordConfig.load(yaml, new NumeralTierCatalog(tiers)).orElseThrow()
                .policy().desiredRoles(60).iterator().next());
    }

    @Test void dottedTierLabelIsReadLiterally() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("numerals:\n  discord-roles:\n    enabled: true\n    mode: highest-only\n    role-ids:\n      'Tier.5': '101'\n");
        NumeralTierCatalog catalog = new NumeralTierCatalog(List.of(new NumeralTierCatalog.Tier("Tier.5", 60, "gray")));
        assertEquals(Set.of("101"), NumeralDiscordConfig.load(yaml, catalog).orElseThrow().policy().desiredRoles(60));
    }
}
