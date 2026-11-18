package org.enthusia.playtime.command;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.model.PlayerProfile;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeenHistoryIntegrationTest {
    @Test void historicalNameFindsAnOnlinePlayerAndShowsPreviousName() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
            PlayerMock viewer = server.addPlayer("Viewer");
            plugin.runtime().writeQueue().prepareHandoff(5);
            PlayerMock player = server.addPlayer("Current");
            viewer.addAttachment(plugin, "playtime.seen", true);
            plugin.runtime().repository().batchUpsertPlayerProfiles(List.of(
                    new PlayerProfile(player.getUniqueId(), "OldName", null, Instant.parse("2025-01-01T00:00:00Z"))));
            while (viewer.nextMessage() != null) { }
            assertTrue(viewer.performCommand("seen OldName"));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 200 && !output.toString().contains("Previously seen as"); i++) {
                server.getScheduler().performTicks(1);
                Thread.sleep(10);
                String message = viewer.nextMessage();
                while (message != null) {
                    output.append(message).append('\n');
                    message = viewer.nextMessage();
                }
            }
            assertTrue(output.toString().contains("currently online"), output.toString());
            assertTrue(output.toString().contains("Previously seen as") && output.toString().contains("OldName"), output.toString());
        } finally {
            MockBukkit.unmock();
        }
    }
}
