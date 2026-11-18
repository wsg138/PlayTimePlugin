package org.enthusia.playtime.command;

import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.data.model.MinuteDelta;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlaytimeCommandIntegrationTest {
    @Test void oneColdCommandEventuallyDeliversTargetPlaytime() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            PlayTimePlugin plugin = MockBukkit.load(PlayTimePlugin.class);
            PlayerMock viewer = server.addPlayer("Viewer");
            PlayerMock target = server.addPlayer("Target");
            viewer.addAttachment(plugin, "playtime.others", true);
            plugin.runtime().repository().batchRecordMinutes(Map.of(target.getUniqueId(), new MinuteDelta(42, 3)), Instant.now());
            plugin.runtime().readService().invalidatePlayer(target.getUniqueId());
            while (viewer.nextMessage() != null) { }
            assertTrue(viewer.performCommand("playtime Target"));
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 200 && !output.toString().contains("Total:"); i++) {
                server.getScheduler().performTicks(1);
                Thread.sleep(10);
                String message = viewer.nextMessage();
                while (message != null) {
                    output.append(message).append('\n');
                    message = viewer.nextMessage();
                }
            }
            assertTrue(output.toString().contains("Playtime for Target"), output.toString());
            assertTrue(output.toString().contains("Total:"), output.toString());
            assertFalse(output.toString().contains("Try again"), output.toString());
        } finally { MockBukkit.unmock(); }
    }
}
