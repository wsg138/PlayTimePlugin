package org.enthusia.playtime;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginSurfaceContractTest {
    private static final Set<String> EXPECTED_COMMANDS = Set.of(
            "playtime",
            "roman",
            "firstjoin",
            "seen"
    );

    private static final Set<String> DEFAULT_TRUE = Set.of(
            "playtime.base",
            "playtime.firstjoined"
    );

    private static final Set<String> DEFAULT_FALSE = Set.of(
            "playtime.others",
            "playtime.seen"
    );

    private static final Set<String> DEFAULT_OP = Set.of(
            "playtime.admin.base",
            "playtime.admin.reload",
            "playtime.admin.debug"
    );

    @Test
    void descriptorKeepsReviewedIdentityAndOptionalDependencies() {
        YamlConfiguration plugin = descriptor();

        assertEquals("org.enthusia.playtime.PlayTimePlugin", plugin.getString("main"));
        assertEquals("1.21", plugin.getString("api-version"));
        assertEquals(
                Set.of("Plan", "PlaceholderAPI", "ProtocolLib", "floodgate", "Geyser-Spigot"),
                Set.copyOf(plugin.getStringList("softdepend"))
        );
    }

    @Test
    void commandSurfaceAliasesAndOuterPermissionBoundaryStayExplicit() {
        ConfigurationSection commands = descriptor().getConfigurationSection("commands");
        assertNotNull(commands);
        assertEquals(EXPECTED_COMMANDS, commands.getKeys(false));

        assertEquals(Set.of("pt"), Set.copyOf(commands.getStringList("playtime.aliases")));
        assertEquals(Set.of("numerals", "rn"), Set.copyOf(commands.getStringList("roman.aliases")));
        assertEquals(Set.of("fj"), Set.copyOf(commands.getStringList("firstjoin.aliases")));

        EXPECTED_COMMANDS.forEach(command -> assertNull(commands.get(command + ".permission"), command));
    }

    @Test
    void permissionDefaultsMatchReviewedAuthoritySurface() {
        ConfigurationSection permissions = descriptor().getConfigurationSection("permissions");
        assertNotNull(permissions);

        Set<String> expected = new HashSet<>();
        expected.addAll(DEFAULT_TRUE);
        expected.addAll(DEFAULT_FALSE);
        expected.addAll(DEFAULT_OP);
        assertEquals(expected, permissions.getKeys(false));

        DEFAULT_TRUE.forEach(permission ->
                assertEquals(Boolean.TRUE, permissions.get(permission + ".default"), permission));
        DEFAULT_FALSE.forEach(permission ->
                assertEquals(Boolean.FALSE, permissions.get(permission + ".default"), permission));
        DEFAULT_OP.forEach(permission ->
                assertEquals("op", permissions.get(permission + ".default"), permission));
    }

    private static YamlConfiguration descriptor() {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
    }
}
