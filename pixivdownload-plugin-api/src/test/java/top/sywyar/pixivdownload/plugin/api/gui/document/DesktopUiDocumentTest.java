package top.sywyar.pixivdownload.plugin.api.gui.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DesktopUiDocumentTest {

    @Test
    @DisplayName("桌面 UI 文档保持页面顺序并冻结输入")
    void documentPreservesOrderAndCopiesInput() {
        var pages = new ArrayList<>(List.of(
                page("status", "test.page.status"),
                page("config", "test.page.config")));

        DesktopUiDocument document = new DesktopUiDocument(pages);
        pages.clear();

        assertThat(document.pages()).extracting(Page::id)
                .containsExactly("status", "config");
        assertThat(document.requiredNodeKinds()).containsExactly(DesktopUiNode.Kind.TEXT);
    }

    @Test
    @DisplayName("桌面 UI 文档拒绝重复的页面标识")
    void documentRejectsDuplicatePageIds() {
        Page status = page("status", "test.page.status");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DesktopUiDocument(List.of(status, status)))
                .withMessageContaining("duplicate page id");
    }

    @Test
    @DisplayName("桌面 UI 文档将对话框纳入稳定词汇验证")
    void documentIncludesDialogNodeKinds() {
        DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                "confirm", DesktopUiNode.TextToken.raw("Confirm"), DesktopUiDocument.DialogStyle.QUESTION,
                new DesktopUiNode.Surface("confirm.surface", DesktopUiNode.SurfaceStyle.WARNING,
                        DesktopUiNode.Insets.all(8), true,
                        new DesktopUiNode.Text("confirm.text", DesktopUiNode.TextToken.raw("Continue?"),
                                DesktopUiNode.TextStyle.BODY, true, false)),
                "confirm.dismiss", true, 420, 0);

        DesktopUiDocument document = new DesktopUiDocument(List.of(page("status", "test.page.status")),
                List.of(dialog));

        assertThat(document.requiredNodeKinds()).containsExactlyInAnyOrder(
                DesktopUiNode.Kind.TEXT, DesktopUiNode.Kind.SURFACE);
    }

    @Test
    @DisplayName("桌面 UI 文档拒绝跨页面重复的节点标识")
    void documentRejectsNodeIdsDuplicatedAcrossPages() {
        Page first = new Page("first", DesktopUiNode.TextToken.raw("First"), text("shared"));
        Page second = new Page("second", DesktopUiNode.TextToken.raw("Second"), text("shared"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DesktopUiDocument(List.of(first, second)))
                .withMessageContaining("duplicate node id: shared");
    }

    @Test
    @DisplayName("桌面 UI 文档拒绝页面与对话框重复的节点标识")
    void documentRejectsNodeIdsDuplicatedAcrossPageAndDialog() {
        Page page = new Page("page", DesktopUiNode.TextToken.raw("Page"), text("shared"));
        DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                "dialog", DesktopUiNode.TextToken.raw("Dialog"), DesktopUiDocument.DialogStyle.INFO,
                text("shared"), "dialog.dismiss", true, 320, 0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DesktopUiDocument(List.of(page), List.of(dialog)))
                .withMessageContaining("duplicate node id: shared");
    }

    @Test
    @DisplayName("键盘序列在错键后可从首键重新匹配")
    void keyboardShortcutAdvancesAndRestarts() {
        DesktopUiDocument.KeyboardShortcut shortcut = new DesktopUiDocument.KeyboardShortcut(
                "shortcut.test", List.of(key("KeyA"), key("KeyB")), "action.test", false);

        assertThat(shortcut.advance(0, key("KeyA")))
                .isEqualTo(new DesktopUiDocument.MatchResult(1, false));
        assertThat(shortcut.advance(1, key("KeyA")))
                .isEqualTo(new DesktopUiDocument.MatchResult(1, false));
        assertThat(shortcut.advance(1, key("KeyB")))
                .isEqualTo(new DesktopUiDocument.MatchResult(0, true));
    }

    @Test
    @DisplayName("桌面 UI 文档冻结完整托盘结构")
    void documentCopiesCompleteTrayStructure() {
        var items = new ArrayList<>(List.of(
                DesktopUiDocument.TrayItem.activate(
                        "tray.show", DesktopUiNode.TextToken.key("test.tray.show")),
                DesktopUiDocument.TrayItem.separator("tray.separator"),
                DesktopUiDocument.TrayItem.dispatch(
                        "tray.exit", DesktopUiNode.TextToken.key("test.tray.exit"), "application.exit")));

        DesktopUiDocument document = new DesktopUiDocument(
                List.of(page("status", "test.page.status")), List.of(), List.of(),
                Optional.of(new DesktopUiDocument.Tray(DesktopUiNode.TextToken.raw("Test"), items)));
        items.clear();

        assertThat(document.tray().orElseThrow().items())
                .extracting(DesktopUiDocument.TrayItem::role)
                .containsExactly(DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW,
                        DesktopUiDocument.TrayItemRole.SEPARATOR,
                        DesktopUiDocument.TrayItemRole.DISPATCH);
    }

    private static DesktopUiDocument.KeyStroke key(String value) {
        return DesktopUiDocument.KeyStroke.key(value);
    }

    private static Page page(String id, String titleKey) {
        return new Page(id, DesktopUiNode.TextToken.key(titleKey),
                text(id + ".content"));
    }

    private static DesktopUiNode.Text text(String id) {
        return new DesktopUiNode.Text(id, DesktopUiNode.TextToken.raw(id),
                DesktopUiNode.TextStyle.BODY, true, false);
    }
}
