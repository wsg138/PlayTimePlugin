package org.enthusia.playtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class FullFeatureCoverageContractTest {
    private static final Map<String, List<String>> REQUIRED_TEST_FAMILIES = requiredTestFamilies();

    @Test
    void everyMajorFeatureFamilyKeepsConcreteRegressionTests() throws IOException {
        Path testRoot = Path.of("src", "test", "java");
        Set<String> testPaths;
        try (Stream<Path> paths = Files.walk(testRoot)) {
            testPaths = paths
                    .filter(Files::isRegularFile)
                    .map(testRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                    .filter(path -> path.endsWith("test.java"))
                    .collect(Collectors.toSet());
        }

        Map<String, List<String>> missing = new LinkedHashMap<>();
        REQUIRED_TEST_FAMILIES.forEach((family, markers) -> {
            boolean covered = markers.stream()
                    .map(marker -> marker.toLowerCase(Locale.ROOT))
                    .anyMatch(marker -> testPaths.stream().anyMatch(path -> path.contains(marker)));
            if (!covered) {
                missing.put(family, markers);
            }
        });

        if (!missing.isEmpty()) {
            fail("PlayTime feature families without a concrete regression-test source: " + missing);
        }
    }

    private static Map<String, List<String>> requiredTestFamilies() {
        Map<String, List<String>> families = new LinkedHashMap<>();
        families.put("plugin runtime and safe reload", List.of("playtimepluginruntime", "configrecovery"));
        families.put("configuration migration and repair", List.of("/config/", "configrecovery"));
        families.put("activity and AFK lifecycle", List.of("/activity/"));
        families.put("playtime accrual accounting", List.of("playtimeaccrual"));
        families.put("SQLite/storage safety and schema migration", List.of("/data/"));
        families.put("async write queue and shutdown recovery", List.of("asyncwritequeue", "shutdownrecovery"));
        families.put("numeral/tier configuration and progression", List.of("numeral", "tiercolor", "tierprogress"));
        families.put("read-service bounds and leaderboard caching", List.of("playtimereadservice"));
        families.put("PlaceholderAPI continuity across reload", List.of("placeholderreload"));
        families.put("SQLite packaging/runtime identity", List.of("sqlitepackaging"));
        families.put("seen command boundary behavior", List.of("seencommand"));
        return Map.copyOf(families);
    }
}
