package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.Page;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.PageKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument.ScrollPolicy;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AppDesktopUiHostDocumentTest {

    @Test
    @DisplayName("宿主声明引导期桌面根页面的完整顺序")
    void hostOwnsOnboardingDocumentOrder() {
        var document = AppDesktopUiHost.desktopUiDocument(false);

        assertThat(document.pages()).extracting(Page::kind).containsExactly(
                PageKind.WELCOME, PageKind.STATUS, PageKind.CONFIG, PageKind.PLUGINS,
                PageKind.TOOLS, PageKind.SECURITY, PageKind.ABOUT);
        assertThat(document.pages().get(1).scrollPolicy()).isEqualTo(ScrollPolicy.SCROLL_PANE);
    }

    @Test
    @DisplayName("宿主在引导完成后从根页面文档移除欢迎页")
    void completedOnboardingOmitsWelcomePage() {
        assertThat(AppDesktopUiHost.desktopUiDocument(true).pages()).extracting(Page::kind).containsExactly(
                PageKind.STATUS, PageKind.CONFIG, PageKind.PLUGINS,
                PageKind.TOOLS, PageKind.SECURITY, PageKind.ABOUT);
    }

    @Test
    @DisplayName("宿主声明的页面标题均可由宿主语言资源解析")
    void hostOwnedPageTitlesResolveForEveryVisibleLocale() {
        var pages = AppDesktopUiHost.desktopUiDocument(false).pages();

        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US, Locale.JAPAN,
                Locale.KOREA, Locale.TRADITIONAL_CHINESE)) {
            assertThat(pages).allSatisfy(page -> assertThat(MessageBundles.get(locale, page.titleI18nKey()))
                    .isNotBlank()
                    .isNotEqualTo(page.titleI18nKey()));
        }
    }
}
