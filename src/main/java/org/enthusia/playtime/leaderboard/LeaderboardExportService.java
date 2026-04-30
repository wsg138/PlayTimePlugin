package org.enthusia.playtime.leaderboard;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PublicLeaderboardEntry;
import org.enthusia.playtime.util.TimeFormats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class LeaderboardExportService {

    private static final String BOARD = "playtime";
    private static final String EXPORT_METRIC = "ACTIVE";
    private static final String EXPORT_RANGE = "ALL";
    private static final int EXPORT_LIMIT = 100;

    private final JavaPlugin plugin;
    private final PlaytimeRepository repository;
    private final PlaytimeConfig.LeaderboardExport config;
    private final Path exportDirectory;

    public LeaderboardExportService(JavaPlugin plugin, PlaytimeRepository repository, PlaytimeConfig.LeaderboardExport config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.exportDirectory = plugin.getDataFolder().toPath().resolve(config.directory()).normalize();
    }

    public void exportAll() {
        if (!config.enabled()) {
            return;
        }

        try {
            Files.createDirectories(exportDirectory);
            Instant generatedAt = Instant.now();
            StringBuilder index = new StringBuilder();
            index.append("{\n");
            appendField(index, 1, "schemaVersion", 1).append(",\n");
            appendField(index, 1, "generatedAt", generatedAt.toString()).append(",\n");
            appendField(index, 1, "source", plugin.getName()).append(",\n");
            index.append(indent(1)).append("\"boards\": [\n");

            String fileName = boardId() + ".json";
            cleanupLegacyBoardFiles(fileName);
            List<PublicLeaderboardEntry> rows = repository.getPublicLeaderboard(EXPORT_METRIC, EXPORT_RANGE, generatedAt, EXPORT_LIMIT);
            writeAtomic(exportDirectory.resolve(fileName), boardJson(generatedAt, rows));

            index.append(indent(2)).append("{");
            appendInlineField(index, "board", boardId()).append(", ");
            appendInlineField(index, "file", fileName).append(", ");
            appendInlineField(index, "label", boardLabel()).append(", ");
            appendInlineField(index, "statLabel", statLabel()).append(", ");
            appendInlineField(index, "metric", EXPORT_METRIC.toLowerCase(Locale.ROOT)).append(", ");
            appendInlineField(index, "range", EXPORT_RANGE.toLowerCase(Locale.ROOT));
            index.append("}");

            index.append("\n").append(indent(1)).append("]\n");
            index.append("}\n");
            writeAtomic(exportDirectory.resolve("index.json"), index.toString());
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to export public playtime leaderboards: " + exception.getMessage());
        }
    }

    public Path exportDirectory() {
        return exportDirectory;
    }

    private String boardJson(Instant generatedAt, List<PublicLeaderboardEntry> rows) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        appendField(json, 1, "schemaVersion", 1).append(",\n");
        appendField(json, 1, "generatedAt", generatedAt.toString()).append(",\n");
        appendField(json, 1, "board", boardId()).append(",\n");
        appendField(json, 1, "sourceBoard", BOARD).append(",\n");
        appendField(json, 1, "label", boardLabel()).append(",\n");
        appendField(json, 1, "statLabel", statLabel()).append(",\n");
        appendField(json, 1, "metric", EXPORT_METRIC.toLowerCase(Locale.ROOT)).append(",\n");
        appendField(json, 1, "range", EXPORT_RANGE.toLowerCase(Locale.ROOT)).append(",\n");
        appendField(json, 1, "unit", "minutes").append(",\n");
        appendField(json, 1, "order", "desc").append(",\n");
        json.append(indent(1)).append("\"players\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            PublicLeaderboardEntry row = rows.get(i);
            if (i > 0) {
                json.append(",\n");
            }
            appendPlayer(json, row);
        }
        json.append("\n").append(indent(1)).append("]\n");
        json.append("}\n");
        return json.toString();
    }

    private void appendPlayer(StringBuilder json, PublicLeaderboardEntry row) {
        json.append(indent(2)).append("{\n");
        appendField(json, 3, "rank", row.rank).append(",\n");
        appendField(json, 3, "uuid", row.uuid.toString()).append(",\n");
        appendField(json, 3, "username", row.username).append(",\n");
        appendNullableField(json, 3, "displayName", row.displayName).append(",\n");
        appendField(json, 3, "value", row.value).append(",\n");
        appendField(json, 3, "formattedValue", TimeFormats.formatMinutes(row.value)).append(",\n");
        appendField(json, 3, "subtext", "Active playtime").append(",\n");
        json.append(indent(3)).append("\"stats\": {\n");
        appendField(json, 4, "activeMinutes", row.activeMinutes).append("\n");
        json.append(indent(3)).append("},\n");
        appendNullableField(json, 3, "firstSeenAt", row.firstSeen == null ? null : row.firstSeen.toString()).append(",\n");
        appendNullableField(json, 3, "lastSeenAt", row.lastSeen == null ? null : row.lastSeen.toString()).append(",\n");
        appendNullableField(json, 3, "updatedAt", row.updatedAt == null ? null : row.updatedAt.toString()).append("\n");
        json.append(indent(2)).append("}");
    }

    private void writeAtomic(Path file, String content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupLegacyBoardFiles(String keepFileName) throws IOException {
        try (Stream<Path> files = Files.list(exportDirectory)) {
            files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(BOARD + "-") && name.endsWith(".json") && !name.equals(keepFileName);
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            plugin.getLogger().warning("Failed to remove old leaderboard export " + path.getFileName() + ": " + exception.getMessage());
                        }
                    });
        }
    }

    private String boardId() {
        return BOARD + "-active-all";
    }

    private String boardLabel() {
        return "Playtime Active Time - All Time";
    }

    private String statLabel() {
        return "Active Time";
    }

    private StringBuilder appendField(StringBuilder json, int level, String name, String value) {
        return appendNullableField(json, level, name, value);
    }

    private StringBuilder appendField(StringBuilder json, int level, String name, long value) {
        return json.append(indent(level)).append('"').append(escape(name)).append("\": ").append(value);
    }

    private StringBuilder appendNullableField(StringBuilder json, int level, String name, String value) {
        json.append(indent(level)).append('"').append(escape(name)).append("\": ");
        if (value == null) {
            return json.append("null");
        }
        return json.append('"').append(escape(value)).append('"');
    }

    private StringBuilder appendInlineField(StringBuilder json, String name, String value) {
        return json.append('"').append(escape(name)).append("\": \"").append(escape(value)).append('"');
    }

    private String indent(int level) {
        return "  ".repeat(level);
    }

    private String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
