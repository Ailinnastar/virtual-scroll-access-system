package vsas.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.User;
import vsas.model.UserType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityLogTest {
    @TempDir Path directory;

    @Test void reloadPreservesEventFieldsAndOrderWithoutCredentialData() throws Exception {
        Path file = directory.resolve("activity.log");
        User actor = new User("user-1", "name\twith\nlines", "stored-hash-secret",
                "Display Name", "user@example.com", "0412345678", UserType.GENERAL);
        ActivityLog log = new ActivityLog(file);
        log.record(actor, ActivityLog.Action.PROFILE_NAME_CHANGED, "account\t1");
        log.record(actor, ActivityLog.Action.SCROLL_UPLOADED, "scroll\n2");

        ActivityLog reloaded = new ActivityLog(file);
        assertEquals(2, reloaded.list().size());
        assertEquals(ActivityLog.Action.SCROLL_UPLOADED, reloaded.list().get(0).action());
        assertEquals("scroll\n2", reloaded.list().get(0).targetId());
        assertEquals("name\twith\nlines", reloaded.list().get(0).actorUsername());
        assertEquals("account\t1", reloaded.list().get(1).targetId());
        assertFalse(Files.readString(file).contains("stored-hash-secret"));
        assertFalse(Files.readString(file).contains("Display Name"));
    }

    @Test void failedWriteKeepsEventPendingUntilStorageRecovers() throws Exception {
        Path file = directory.resolve("activity.log");
        ActivityLog log = new ActivityLog(file);
        User actor = new User("user-1", "reader", "stored-hash-secret",
                "Display Name", "user@example.com", "0412345678", UserType.GENERAL);
        Files.createDirectory(file);
        assertThrows(IOException.class,
                () -> log.record(actor, ActivityLog.Action.SCROLL_DELETED, "SCR-1"));
        assertEquals(1, log.list().size());
        assertTrue(log.persistencePending());

        Files.delete(file);
        log.ensureAvailable();
        assertFalse(log.persistencePending());
        assertEquals("SCR-1", new ActivityLog(file).list().get(0).targetId());
    }
}
