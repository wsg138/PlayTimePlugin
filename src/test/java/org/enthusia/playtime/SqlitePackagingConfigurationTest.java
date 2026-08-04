package org.enthusia.playtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlitePackagingConfigurationTest {
    private static final Pattern SQLITE_RELOCATION = Pattern.compile(
            "<pattern>\\s*org\\.sqlite\\s*</pattern>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAVEN_VERSION = Pattern.compile(
            "<version>([^<]+)</version>");
    private static final Pattern PLUGIN_VERSION = Pattern.compile(
            "(?m)^version:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");

    @Test
    void sqliteJdbcRemainsBundledButItsJniPackageIsNotRelocated() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains("<artifactId>sqlite-jdbc</artifactId>"),
                "The SQLite JDBC driver must remain bundled in the plugin jar");
        assertFalse(SQLITE_RELOCATION.matcher(pom).find(),
                "Relocating org.sqlite breaks sqlite-jdbc native JNI method resolution");
    }

    @Test
    void pluginMetadataVersionMatchesMavenProjectVersion() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String pluginYml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        Matcher maven = MAVEN_VERSION.matcher(pom);
        Matcher plugin = PLUGIN_VERSION.matcher(pluginYml);

        assertTrue(maven.find(), "Maven project version is missing");
        assertTrue(plugin.find(), "plugin.yml version is missing");
        assertEquals(maven.group(1), plugin.group(1),
                "plugin.yml version must match the Maven project version");
    }
}
