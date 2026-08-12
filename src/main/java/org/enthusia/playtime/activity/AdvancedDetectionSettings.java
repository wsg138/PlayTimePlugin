package org.enthusia.playtime.activity;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;

/**
 * Centralized, validated thresholds for the behavioral detector.
 *
 * <p>PlaytimeConfig keeps the legacy public configuration shape intact. The existing
 * ConfigMigrator merges newly documented defaults into older configs, while this
 * class reads the advanced detector section without scattering magic constants
 * throughout listeners and analyzers.</p>
 */
public record AdvancedDetectionSettings(
        Click click,
        Rotation rotation,
        Movement movement,
        Sequence sequence,
        Scoring scoring) {

    public static AdvancedDetectionSettings load(PlaytimeConfig base) {
        FileConfiguration raw = liveConfiguration();
        PlaytimeConfig.Suspicion legacy = base.sampling().suspicion();

        Click click = new Click(
                bool(raw, "sampling.suspicion.click.enabled", true),
                millis(raw, "sampling.suspicion.click.window-seconds", legacy.windowSeconds(), 5L, 3600L),
                integer(raw, "sampling.suspicion.click.minimum-swings", legacy.minSwings(), 2, 10_000),
                decimal(raw, "sampling.suspicion.click.max-cv", legacy.maxCv(), 0.001D, 1.0D));

        Rotation rotation = new Rotation(
                bool(raw, "sampling.suspicion.rotation.enabled", true),
                millis(raw, "sampling.suspicion.rotation.window-seconds", 30L, 5L, 3600L),
                integer(raw, "sampling.suspicion.rotation.minimum-samples", 10, 4, 512),
                decimal(raw, "sampling.suspicion.rotation.max-cv", 0.06D, 0.001D, 1.0D));

        Movement movement = new Movement(
                bool(raw, "sampling.suspicion.movement.enabled", true),
                millis(raw, "sampling.suspicion.movement.window-seconds", 45L, 5L, 3600L),
                integer(raw, "sampling.suspicion.movement.minimum-samples", 24, 8, 512),
                integer(raw, "sampling.suspicion.movement.minimum-cycles", 4, 3, 12),
                decimal(raw, "sampling.suspicion.movement.similarity-threshold", 0.96D, 0.75D, 0.9999D),
                integer(raw, "sampling.suspicion.movement.maximum-cycle-samples", 32, 4, 96),
                millisRaw(raw, "sampling.suspicion.movement.minimum-cycle-millis", 900L, 250L, 60_000L));

        Sequence sequence = new Sequence(
                bool(raw, "sampling.suspicion.sequence.enabled", true),
                millis(raw, "sampling.suspicion.sequence.window-seconds", 60L, 5L, 3600L),
                integer(raw, "sampling.suspicion.sequence.minimum-actions", 12, 6, 512),
                integer(raw, "sampling.suspicion.sequence.minimum-repetitions", 3, 3, 12),
                decimal(raw, "sampling.suspicion.sequence.similarity-threshold", 0.95D, 0.75D, 0.9999D),
                integer(raw, "sampling.suspicion.sequence.maximum-cycle-samples", 40, 4, 128));

        Scoring scoring = new Scoring(
                decimal(raw, "sampling.suspicion.scoring.suspicious-threshold", 0.86D, 0.5D, 1.0D),
                decimal(raw, "sampling.suspicion.scoring.clear-threshold", 0.30D, 0.0D, 0.8D),
                decimal(raw, "sampling.suspicion.scoring.decay-per-second", 0.025D, 0.001D, 1.0D),
                millisRaw(raw, "sampling.suspicion.scoring.analysis-interval-ms", 1000L, 250L, 10_000L),
                integer(raw, "sampling.suspicion.scoring.history-size", 256, 64, 1024),
                millis(raw, "sampling.suspicion.scoring.recovery-seconds", 15L, 3L, 300L),
                millis(raw, "sampling.suspicion.scoring.reconnect-retention-seconds", 300L, 30L, 3600L),
                integer(raw, "sampling.suspicion.scoring.max-retained-disconnected", 512, 32, 4096));

        if (scoring.clearThreshold() >= scoring.suspiciousThreshold()) {
            scoring = new Scoring(scoring.suspiciousThreshold(),
                    Math.max(0.0D, scoring.suspiciousThreshold() * 0.35D),
                    scoring.decayPerSecond(), scoring.analysisIntervalMillis(),
                    scoring.historySize(), scoring.recoveryMillis(),
                    scoring.reconnectRetentionMillis(), scoring.maxRetainedDisconnected());
        }
        return new AdvancedDetectionSettings(click, rotation, movement, sequence, scoring);
    }

    private static FileConfiguration liveConfiguration() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("EnthusiaPlaytime");
            return plugin instanceof JavaPlugin javaPlugin ? javaPlugin.getConfig() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean bool(FileConfiguration cfg, String path, boolean fallback) {
        return cfg == null ? fallback : cfg.getBoolean(path, fallback);
    }

    private static int integer(FileConfiguration cfg, String path, int fallback, int min, int max) {
        int value = cfg == null ? fallback : cfg.getInt(path, fallback);
        return Math.max(min, Math.min(max, value));
    }

    private static double decimal(FileConfiguration cfg, String path, double fallback,
                                  double min, double max) {
        double value = cfg == null ? fallback : cfg.getDouble(path, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long millis(FileConfiguration cfg, String path, long fallbackSeconds,
                               long minSeconds, long maxSeconds) {
        long value = cfg == null ? fallbackSeconds : cfg.getLong(path, fallbackSeconds);
        return Math.max(minSeconds, Math.min(maxSeconds, value)) * 1000L;
    }

    private static long millisRaw(FileConfiguration cfg, String path, long fallback,
                                  long min, long max) {
        long value = cfg == null ? fallback : cfg.getLong(path, fallback);
        return Math.max(min, Math.min(max, value));
    }

    public record Click(boolean enabled, long windowMillis, int minimumSwings, double maxCv) { }

    public record Rotation(boolean enabled, long windowMillis, int minimumSamples, double maxCv) { }

    public record Movement(boolean enabled,
                           long windowMillis,
                           int minimumSamples,
                           int minimumCycles,
                           double similarityThreshold,
                           int maximumCycleSamples,
                           long minimumCycleMillis) { }

    public record Sequence(boolean enabled,
                           long windowMillis,
                           int minimumActions,
                           int minimumRepetitions,
                           double similarityThreshold,
                           int maximumCycleSamples) { }

    public record Scoring(double suspiciousThreshold,
                          double clearThreshold,
                          double decayPerSecond,
                          long analysisIntervalMillis,
                          int historySize,
                          long recoveryMillis,
                          long reconnectRetentionMillis,
                          int maxRetainedDisconnected) { }
}
