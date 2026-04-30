package org.enthusia.playtime.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ExtensionService;
import org.bukkit.Bukkit;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.config.PlaytimeConfig;
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.service.PlaytimeReadService;

import java.util.logging.Level;

public final class PlanHook implements AutoCloseable {

    private final PlayTimePlugin plugin;
    private final PlaytimeRepository repository;
    private final PlaytimeReadService readService;
    private final SessionManager sessionManager;
    private final PlaytimeConfig config;

    private DataExtension extension;
    private boolean enableListenerRegistered;
    private volatile boolean closed;
    private volatile boolean registrationWarningLogged;

    public PlanHook(PlayTimePlugin plugin,
                    PlaytimeRepository repository,
                    PlaytimeReadService readService,
                    SessionManager sessionManager,
                    PlaytimeConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.readService = readService;
        this.sessionManager = sessionManager;
        this.config = config;
    }

    public void hook() {
        closed = false;
        if (!config.isPlanIntegrationEnabled()) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return;
        }

        try {
            if (!hasRequiredCapabilities()) {
                plugin.getLogger().info("Plan is installed, but required DataExtension capabilities are unavailable.");
                return;
            }
            listenForPlanReloads();
            registerDataExtension();
        } catch (NoClassDefFoundError ignored) {
            // Plan is optional and not present on this server.
        } catch (IllegalStateException exception) {
            logRegistrationWarning("Plan integration skipped because Plan is not ready: " + exception.getMessage(), exception);
        } catch (Exception exception) {
            logRegistrationWarning("Failed to register Plan analytics integration.", exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (extension == null) {
            return;
        }
        try {
            ExtensionService.getInstance().unregister(extension);
        } catch (NoClassDefFoundError | IllegalStateException ignored) {
            // Plan is optional and may already be disabled.
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to unregister Plan analytics integration.", exception);
        } finally {
            extension = null;
        }
    }

    private void registerDataExtension() {
        close();
        extension = new PlaytimePlanExtension(repository, readService, sessionManager);
        ExtensionService.getInstance().register(extension);
        registrationWarningLogged = false;
        plugin.getLogger().info("Registered Plan Player Analytics data extension.");
    }

    private void listenForPlanReloads() {
        if (enableListenerRegistered) {
            return;
        }
        enableListenerRegistered = true;
        CapabilityService.getInstance().registerEnableListener(isPlanEnabled -> {
            if (isPlanEnabled && !closed && config.isPlanIntegrationEnabled()) {
                try {
                    registerDataExtension();
                } catch (Exception exception) {
                    logRegistrationWarning("Failed to re-register Plan analytics integration after Plan reload.", exception);
                }
            }
        });
    }

    private boolean hasRequiredCapabilities() {
        CapabilityService capabilities = CapabilityService.getInstance();
        return capabilities.hasCapability("DATA_EXTENSION_VALUES")
                && capabilities.hasCapability("DATA_EXTENSION_TABLES")
                && capabilities.hasCapability("DATA_EXTENSION_SHOW_IN_PLAYER_TABLE");
    }

    private void logRegistrationWarning(String message, Exception exception) {
        if (registrationWarningLogged) {
            return;
        }
        registrationWarningLogged = true;
        plugin.getLogger().log(Level.WARNING, message, exception);
    }
}
