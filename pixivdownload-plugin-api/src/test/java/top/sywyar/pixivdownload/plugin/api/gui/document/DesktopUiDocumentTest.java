package top.sywyar.pixivdownload.plugin.api.gui.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.Page;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.PageKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.ScrollPolicy;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DesktopUiDocumentTest {

    @Test
    @DisplayName("桌面 UI 文档保持页面顺序并冻结输入")
    void documentPreservesOrderAndCopiesInput() {
        var pages = new ArrayList<>(List.of(
                new Page(PageKind.STATUS, "test.page.status", ScrollPolicy.SCROLL_PANE),
                new Page(PageKind.CONFIG, "test.page.config", ScrollPolicy.NONE)));

        DesktopUiDocument document = new DesktopUiDocument(pages);
        pages.clear();

        assertThat(document.pages()).extracting(Page::kind)
                .containsExactly(PageKind.STATUS, PageKind.CONFIG);
    }

    @Test
    @DisplayName("桌面 UI 文档拒绝重复的页面语义")
    void documentRejectsDuplicatePageKinds() {
        Page status = new Page(PageKind.STATUS, "test.page.status", ScrollPolicy.SCROLL_PANE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DesktopUiDocument(List.of(status, status)))
                .withMessageContaining("duplicate page kind");
    }
}
