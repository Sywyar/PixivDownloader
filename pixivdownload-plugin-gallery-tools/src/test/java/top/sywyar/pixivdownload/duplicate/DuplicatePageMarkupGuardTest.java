package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("duplicate 页面静态守卫")
class DuplicatePageMarkupGuardTest {

    private static final String HTML = "static/pixiv-duplicates.html";

    private static String read(String resource) throws IOException {
        try (InputStream in = DuplicatePageMarkupGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("页面声明疑似重复页顶部图标 slot，并加载共享导航 / Vue helper / 页面模块")
    void pageDeclaresNavigationSlotAndScripts() throws IOException {
        String html = read(HTML);
        assertThat(html).contains("data-nav-slot=\"" + NavigationPlacements.DUPLICATES_HEADER_ICONS + "\"");
        for (String ref : List.of(
                "/js/pixiv-i18n.js",
                "/js/pixiv-vue.js",
                "/js/pixiv-navigation.js",
                "/pixiv-duplicates/pixiv-duplicates.css",
                "/pixiv-duplicates/pixiv-duplicates.js")) {
            assertThat(html).as("pixiv-duplicates.html 应引用 " + ref).contains(ref);
        }
    }

    @Test
    @DisplayName("页面不硬编码跨插件入口 href，画廊 / 统计图标入口由动态 slot 提供")
    void pageDoesNotHardcodeCrossPluginEntries() throws IOException {
        String html = read(HTML);
        for (String href : List.of(
                "/pixiv-gallery.html",
                "/pixiv-stats.html",
                "/monitor.html",
                "/pixiv-novel-gallery.html",
                "/pixiv-invite-manage.html")) {
            assertThat(html)
                    .as("疑似重复页不应硬编码跨插件入口 %s", href)
                    .doesNotContain("href=\"" + href + "\"")
                    .doesNotContain("href=\"" + href + "?");
        }
    }
}
