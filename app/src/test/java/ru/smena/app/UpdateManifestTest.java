package ru.smena.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private final String hash = "a".repeat(64), commit = "b".repeat(40);

    @Test public void rejectsUnboundedFilesAndUntrustedCommitPaths() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "1.3", hash, 0, commit));
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "1.3", hash, UpdateManifest.MAX_APK_BYTES + 1, commit));
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "1.3", hash, 100, "../main"));
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "1.3", "bad", 100, commit));
    }

    @Test public void rejectsInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(0, "1.3", hash, 100, commit));
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "", hash, 100, commit));
        assertThrows(IllegalArgumentException.class, () -> new UpdateManifest(4, "unknown", hash, 100, commit));
    }
}
