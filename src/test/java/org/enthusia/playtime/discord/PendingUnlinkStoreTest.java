package org.enthusia.playtime.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingUnlinkStoreTest {
    private static final NumeralRoleAccountRef FIRST = new NumeralRoleAccountRef("provider:account-1");
    private static final NumeralRoleAccountRef SECOND = new NumeralRoleAccountRef("provider:account-2");
    private static final NumeralRoleAccountRef LEGACY = new NumeralRoleAccountRef("1552382344386584576");

    @TempDir Path directory;

    @Test
    void survivesRestartAndReadsLegacyDiscordQueue() throws Exception {
        File file = directory.resolve("unlinks.yml").toFile();
        PendingUnlinkStore store = new PendingUnlinkStore(file);
        assertTrue(store.load().isEmpty());

        store.save(Set.of(FIRST, SECOND));
        assertEquals(Set.of(FIRST, SECOND), new PendingUnlinkStore(file).load());

        Files.writeString(directory.resolve("unlinks.yml.tmp"),
                "discord-ids:\n  - '1552382344386584576'\n");
        assertEquals(Set.of(FIRST, SECOND, LEGACY), store.load());
    }

    @Test
    void malformedQueueIsNotSilentlyDiscarded() throws Exception {
        File file = directory.resolve("unlinks.yml").toFile();
        PendingUnlinkStore store = new PendingUnlinkStore(file);
        Files.writeString(file.toPath(), "account-refs: [broken");
        assertThrows(Exception.class, store::load);
    }

    @Test
    void invalidOpaqueReferenceIsRejected() throws Exception {
        File file = directory.resolve("unlinks.yml").toFile();
        PendingUnlinkStore store = new PendingUnlinkStore(file);
        Files.writeString(file.toPath(), "account-refs:\n  - \"bad\\u0001ref\"\n");
        assertThrows(Exception.class, store::load);
    }
}
