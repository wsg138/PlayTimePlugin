package org.enthusia.playtime.discord;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A small crash-safe queue for unlink cleanup that cannot be reconstructed from current links. */
final class PendingUnlinkStore {
    private static final String ACCOUNT_REFS_KEY = "account-refs";
    private static final String LEGACY_DISCORD_IDS_KEY = "discord-ids";

    private final File file;

    PendingUnlinkStore(File file) { this.file = file; }

    Set<NumeralRoleAccountRef> load() throws IOException {
        Set<NumeralRoleAccountRef> result = new HashSet<>();
        result.addAll(loadFile(file));
        result.addAll(loadFile(temporaryFile()));
        return Set.copyOf(result);
    }

    private Set<NumeralRoleAccountRef> loadFile(File source) throws IOException {
        if (!source.isFile()) return Set.of();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid pending numeral-role unlink queue", exception);
        }

        Set<NumeralRoleAccountRef> result = new HashSet<>();
        addReferences(result, yaml.getStringList(ACCOUNT_REFS_KEY));
        addReferences(result, yaml.getStringList(LEGACY_DISCORD_IDS_KEY));
        return Set.copyOf(result);
    }

    private static void addReferences(Set<NumeralRoleAccountRef> target, List<String> values) throws IOException {
        for (String value : values) {
            try {
                target.add(new NumeralRoleAccountRef(value));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid account reference in pending numeral-role unlink queue", exception);
            }
        }
    }

    void save(Set<NumeralRoleAccountRef> accounts) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = temporaryFile();
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> values = accounts.stream()
                .map(NumeralRoleAccountRef::value)
                .sorted()
                .toList();
        yaml.set(ACCOUNT_REFS_KEY, values);
        yaml.save(temporary);
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File temporaryFile() {
        return new File(file.getParentFile(), file.getName() + ".tmp");
    }
}
