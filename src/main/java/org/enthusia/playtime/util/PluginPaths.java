package org.enthusia.playtime.util;

import java.nio.file.Path;

public final class PluginPaths {
    private PluginPaths() {
    }

    public static Path resolveInside(Path dataFolder, String configuredPath, String description) {
        Path root = dataFolder.toAbsolutePath().normalize();
        Path configured = Path.of(configuredPath == null ? "" : configuredPath);
        if (configured.isAbsolute()) {
            throw new IllegalArgumentException(description + " must be relative to the plugin data folder");
        }
        Path resolved = root.resolve(configured).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(description + " escapes the plugin data folder: " + configuredPath);
        }
        return resolved;
    }
}
