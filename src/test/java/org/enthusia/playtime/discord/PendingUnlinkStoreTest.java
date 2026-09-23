package org.enthusia.playtime.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PendingUnlinkStoreTest {
    @TempDir Path directory;

    @Test void survivesRestartAndDoesNotDiscardMalformedQueue() throws Exception {
        File file = directory.resolve("unlinks.yml").toFile();
        PendingUnlinkStore store = new PendingUnlinkStore(file);
        assertTrue(store.load().isEmpty());
        store.save(Set.of("1552382278712168448", "1552382306587775068"));
        assertEquals(Set.of("1552382278712168448", "1552382306587775068"),
                new PendingUnlinkStore(file).load());
        Files.writeString(directory.resolve("unlinks.yml.tmp"), "discord-ids:\n  - '1552382344386584576'\n");
        assertEquals(Set.of("1552382278712168448", "1552382306587775068", "1552382344386584576"),
                store.load());
        Files.delete(directory.resolve("unlinks.yml.tmp"));
        Files.writeString(file.toPath(), "discord-ids: [broken");
        assertThrows(Exception.class, store::load);
    }
}
