package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiActionResponseSafetyTest {
    @Test
    void genericCredentialPathsAreRejected() {
        for (String path : new String[]{"result.sessionId", "result.PHPSESSID", "auth.bearer",
                "credentials.access-key", "credentials.access_key_id", "keys.signing-key",
                "keys.encryptionKey", "keys.decryption_key"}) {
            assertFalse(GuiActionResponseSafety.safeJsonPath(path, false, true), path);
        }
    }

    @Test
    void ordinaryStructuredResultPathsRemainAllowed() {
        for (String path : new String[]{"reply", "result.status", "results.channel",
                "diagnostics.reachable", "metrics.elapsed_ms"}) {
            assertTrue(GuiActionResponseSafety.safeJsonPath(path, false, true), path);
        }
    }

    @Test
    void displayTextIsBoundedPlainText() {
        assertEquals("‹html›‹script›alert(1)‹/script›", GuiActionResponseSafety.sanitizeActionText(
                "  <html><script>alert\u0000(1)</script>  "));
    }

    @Test
    void displayTextIsTruncatedByCodePoint() {
        String sanitized = GuiActionResponseSafety.sanitizeActionText(
                "😀".repeat(GuiActionResponseSafety.MAX_ACTION_TEXT_CODE_POINTS + 10));
        assertEquals(GuiActionResponseSafety.MAX_ACTION_TEXT_CODE_POINTS + 1,
                sanitized.codePointCount(0, sanitized.length()));
        assertTrue(sanitized.endsWith("…"));
    }
}
