package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("桌面渲染上下文能力协商")
class DesktopUiContextTest {

    @Test
    @DisplayName("每次读取新文档版本都会重新校验提供者能力")
    void validatesProviderCapabilitiesForEveryDocumentRead() {
        MutableModel model = new MutableModel(document(text("initial")));
        DesktopUiContext context = context(model, Set.of(DesktopUiNode.Kind.TEXT), Set.of());

        assertThat(context.currentDocument()).isSameAs(model.document());

        model.document = document(new DesktopUiNode.Split(
                "split", DesktopUiNode.Axis.HORIZONTAL, .5, text("first"), text("second")));
        model.revision++;

        assertThatThrownBy(context::currentDocument)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-provider")
                .hasMessageContaining("SPLIT")
                .hasMessageContaining("SPLIT_USER_RESIZABLE");
    }

    @Test
    @DisplayName("初始文档缺少语义能力时立即拒绝启动")
    void rejectsUnsupportedInitialDocument() {
        MutableModel model = new MutableModel(document(new DesktopUiNode.Split(
                "split", DesktopUiNode.Axis.HORIZONTAL, .5, text("first"), text("second"))));

        assertThatThrownBy(() -> context(model,
                Set.of(DesktopUiNode.Kind.SPLIT, DesktopUiNode.Kind.TEXT), Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPLIT_USER_RESIZABLE");
    }

    private static DesktopUiContext context(MutableModel model, Set<DesktopUiNode.Kind> kinds,
                                            Set<DesktopUiCapability> capabilities) {
        return new DesktopUiContext(false, "Test", model, DesktopUiNode.TextToken::fallback,
                () -> { }, () -> "system", "test-provider", kinds, capabilities);
    }

    private static DesktopUiDocument document(DesktopUiNode content) {
        return new DesktopUiDocument(List.of(new DesktopUiDocument.Page(
                "page", DesktopUiNode.TextToken.raw("Page"), content)));
    }

    private static DesktopUiNode.Text text(String id) {
        return new DesktopUiNode.Text(id, DesktopUiNode.TextToken.raw(id),
                DesktopUiNode.TextStyle.BODY, true, false);
    }

    private static final class MutableModel implements DesktopUiModel {
        private DesktopUiDocument document;
        private long revision;

        private MutableModel(DesktopUiDocument document) {
            this.document = document;
        }

        @Override public DesktopUiDocument document() { return document; }
        @Override public long revision() { return revision; }
        @Override public void dispatch(DesktopUiNode.Event event) { }
    }
}
