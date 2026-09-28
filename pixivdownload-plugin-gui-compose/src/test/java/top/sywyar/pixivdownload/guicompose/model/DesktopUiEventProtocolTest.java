package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 页面事件索引复用")
class DesktopUiEventProtocolTest {
    @Test
    @DisplayName("表单遍历保留字段与尾随动作的顺序、不可变性和跨行标识校验")
    void formTraversalPreservesFieldsAndTrailingActions() {
        var label = DesktopUiNode.TextToken.raw("Action");
        var first = actionButton("first");
        var trailing = actionButton("trailing");
        var second = actionButton("second");
        var form = new DesktopUiNode.Form(
                "form",
                DesktopUiNode.FormStyle.RESPONSIVE,
                null,
                List.of(
                        new DesktopUiNode.FormRow("row.first", label, null, first, trailing),
                        new DesktopUiNode.FormRow("row.second", label, null, second, null)
                )
        );
        assertEquals(List.of(first, trailing, second), form.childNodes());
        assertThrows(UnsupportedOperationException.class, () -> form.childNodes().clear());
        var document = new DesktopUiDocument(List.of(new DesktopUiDocument.Page("page", label, form)));
        var endpoints = DesktopUiEventProtocol.index(document);
        assertEquals("trailing.action", endpoints.get("trailing").targetId());
        assertEquals("second.action", endpoints.get("second").targetId());
        var duplicate = new DesktopUiNode.Form(
                "duplicate",
                DesktopUiNode.FormStyle.RESPONSIVE,
                null,
                List.of(
                        new DesktopUiNode.FormRow("row.one", label, null, first, trailing),
                        new DesktopUiNode.FormRow("row.two", label, null, trailing, null)
                )
        );
        assertThrows(IllegalArgumentException.class, () -> DesktopUiNode.validateTree(duplicate));
    }

    private static DesktopUiNode.Button actionButton(String id) {
        return new DesktopUiNode.Button(
                id,
                id + ".action",
                DesktopUiNode.TextToken.raw("Action"),
                null,
                DesktopUiNode.ButtonStyle.NORMAL,
                true
        );
    }

    @Test
    @DisplayName("复用端点仍拒绝重复标识，输入约束与来源换代仍更新签名")
    void retainsValidationAcrossReusedPages() {
        var cache = new IdentityHashMap<DesktopUiNode, Map<String, DesktopUiEventProtocol.EventEndpoint>>();
        var toggle = new DesktopUiNode.Toggle(
                "toggle",
                "binding",
                DesktopUiNode.TextToken.raw("Enabled"),
                null,
                DesktopUiNode.ToggleStyle.SWITCH,
                false,
                true
        );
        var page = new DesktopUiDocument.Page("settings", DesktopUiNode.TextToken.raw("Settings"), toggle);
        var document = new DesktopUiDocument(List.of(page));
        var first = DesktopUiEventProtocol.index(document, cache);
        var sources = List.of(new DesktopUiPluginSnapshot.Fingerprint("owner", false, "package", 1));
        var signatures = DesktopUiEventProtocol.interactionSignatures(first, sources);
        var next = DesktopUiEventProtocol.index(document, cache);
        assertSame(first.get("toggle"), next.get("toggle"));
        assertSame(signatures.get("toggle"), DesktopUiEventProtocol.interactionSignatures(
                next, sources, first, signatures
        ).get("toggle"));
        var replacedSources = List.of(new DesktopUiPluginSnapshot.Fingerprint("owner", false, "package", 2));
        assertNotEquals(signatures, DesktopUiEventProtocol.interactionSignatures(next, replacedSources, first, signatures));
        var duplicate = new DesktopUiDocument(
                List.of(page),
                List.of(),
                List.of(),
                Optional.of(new DesktopUiDocument.Tray(
                        DesktopUiNode.TextToken.raw("Tray"),
                        List.of(new DesktopUiDocument.TrayItem(
                                "toggle",
                                DesktopUiNode.TextToken.raw("Action"),
                                DesktopUiDocument.TrayItemRole.DISPATCH,
                                "action"
                        ))
                )),
                true
        );
        assertThrows(IllegalArgumentException.class, () -> DesktopUiEventProtocol.index(duplicate, cache));
        var disabled = new DesktopUiNode.Toggle(
                "toggle",
                "binding",
                DesktopUiNode.TextToken.raw("Enabled"),
                null,
                DesktopUiNode.ToggleStyle.SWITCH,
                false,
                false
        );
        var changed = DesktopUiEventProtocol.index(new DesktopUiDocument(List.of(
                new DesktopUiDocument.Page("settings", DesktopUiNode.TextToken.raw("Settings"), disabled)
        )), cache);
        assertFalse(changed.get("toggle").enabled());
        assertNotEquals(signatures, DesktopUiEventProtocol.interactionSignatures(changed, sources, next, signatures));
        assertEquals(1, cache.size());
        assertFalse(cache.containsKey(toggle));
    }
}
