package org.enthusia.playtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlitePackagingConfigurationTest {
    private static final Pattern SQLITE_RELOCATION = Pattern.compile(
            "<pattern>\\s*org\\.sqlite\\s*</pattern>", Pattern.CASE_INSENSITIVE);

    @Test
    void sqliteJdbcRemainsBundledButItsJniPackageIsNotRelocated() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains("<artifactId>sqlite-jdbc</artifactId>"),
                "The SQLite JDBC driver must remain bundled in the plugin jar");
        assertFalse(SQLITE_RELOCATION.matcher(pom).find(),
                "Relocating org.sqlite breaks sqlite-jdbc native JNI method resolution");
    }
}
