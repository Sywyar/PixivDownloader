package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiActionResponseSafetyTest {
    @Test
    @org.junit.jupiter.api.DisplayName("候选值保留原始标识，超限或错误状态不提供回填")
    void selectionValuesAreBoundedAndNeverTruncated() {
        var spec = top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary
                .allItems("items", "id", "").selectInto("demo.value");
        var body = top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiValue.of(
                java.util.Map.of("items", java.util.List.of(java.util.Map.of("id", "test/<模型>"))));
        var response = new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiResponse(true, 200, body, "", false);
        assertEquals(java.util.List.of("test/<模型>"), GuiActionResponseSafety.ActionResult.from(response, spec).selectionValues(spec));
        for (var bad : java.util.List.of(
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiResponse(true, 500, body, "", false),
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiResponse(true, 200, body, "", true),
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiResponse(true, 200,
                        top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiValue.of(java.util.Map.of("items",
                                java.util.Collections.nCopies(2001, java.util.Map.of("id", "test-one")))), "", false)
        )) {
            assertTrue(GuiActionResponseSafety.ActionResult.from(bad, spec).selectionValues(spec).isEmpty());
        }
    }

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
