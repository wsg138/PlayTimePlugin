package org.enthusia.playtime.discord;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** A small crash-safe queue for unlink cleanup that cannot be reconstructed from current links. */
final class PendingUnlinkStore {
    private final File file;

    PendingUnlinkStore(File file) { this.file = file; }

    Set<String> load() throws IOException {
        Set<String> result = new HashSet<>();
        result.addAll(loadFile(file));
        result.addAll(loadFile(temporaryFile()));
        return result;
    }

    private Set<String> loadFile(File source) throws IOException {
        if (!source.isFile()) return Set.of();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid pending Discord unlink queue", exception);
        }
        Set<String> result = new HashSet<>();
        for (String id : yaml.getStringList("discord-ids")) {
            if (!id.matches("[0-9]{1,20}")) throw new IOException("Invalid Discord ID in pending unlink queue");
            result.add(id);
        }
        return result;
    }

    void save(Set<String> ids) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = temporaryFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("discord-ids", new ArrayList<>(ids));
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
