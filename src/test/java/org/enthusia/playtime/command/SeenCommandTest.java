package org.enthusia.playtime.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeenCommandTest {
    private static final UUID PLAYER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void permissionDenialStopsBeforeRuntimeLookup() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("playtime.seen")).thenReturn(false);

        SeenCommand command = new SeenCommand(plugin);
        command.onCommand(sender, null, "seen", new String[0]);

        verify(sender).sendMessage(contains("You don't have permission"));
        verify(plugin, never()).runtime();
    }

    @Test
    void unavailableRuntimeFailsClosed() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("playtime.seen")).thenReturn(true);
        when(plugin.runtime()).thenReturn(null);

        SeenCommand command = new SeenCommand(plugin);
        command.onCommand(sender, null, "seen", new String[0]);

        verify(sender).sendMessage(contains("runtime is not available"));
    }

    @Test
    void consoleWithoutTargetGetsUsage() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        PlaytimeRuntime runtime = mock(PlaytimeRuntime.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("playtime.seen")).thenReturn(true);
        when(plugin.runtime()).thenReturn(runtime);

        SeenCommand command = new SeenCommand(plugin);
        command.onCommand(sender, null, "seen", new String[0]);

        verify(sender).sendMessage(contains("Usage: /seen <player>"));
    }

    @Test
    void onlinePlayerWithoutTargetGetsImmediateOnlineStatus() {
        PlayTimePlugin plugin = mock(PlayTimePlugin.class);
        PlaytimeRuntime runtime = mock(PlaytimeRuntime.class);
        Player player = mock(Player.class);
        when(player.hasPermission("playtime.seen")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("ExamplePlayer");
        when(plugin.runtime()).thenReturn(runtime);

        SeenCommand command = new SeenCommand(plugin);
        command.onCommand(player, null, "seen", new String[0]);

        verify(player).sendMessage(contains("ExamplePlayer"));
        verify(player).sendMessage(contains("currently online"));
    }
}
