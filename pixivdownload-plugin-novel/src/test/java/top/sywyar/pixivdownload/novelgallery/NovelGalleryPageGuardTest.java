package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说画廊资源接线守卫")
class NovelGalleryPageGuardTest {

    private static final List<String> STYLESHEETS = List.of(
            "/pixiv-novel-gallery/pixiv-novel-gallery.css",
            "/pixiv-novel-gallery/pixiv-novel-gallery-sidebar.css",
            "/pixiv-novel-gallery/pixiv-novel-gallery-content.css",
            "/pixiv-novel-gallery/pixiv-novel-gallery-overlays.css",
            "/pixiv-novel-gallery/pixiv-novel-gallery-responsive.css"
    );

    @Test
    @DisplayName("页面按职责顺序加载样式并保留主题与响应式入口")
    void stylesheetsKeepResponsibilityOrderAndPageCapabilities() throws IOException {
        String html = read("pixiv-novel-gallery.html");
        int previous = -1;
        for (String stylesheet : STYLESHEETS) {
            int current = html.indexOf("href=\"" + stylesheet + "\"");
            assertThat(current).as("pixiv-novel-gallery.html 应加载 " + stylesheet)
                    .isGreaterThan(previous);
            previous = current;
            assertThat(read(stylesheet.substring(1))).as(stylesheet).isNotBlank();
        }
        assertThat(read("pixiv-novel-gallery/pixiv-novel-gallery.css"))
                .contains("html[data-theme=\"dark\"]", "--surface");
        assertThat(read("pixiv-novel-gallery/pixiv-novel-gallery-responsive.css"))
                .contains("@media (max-width: 768px)");
    }

    private String read(String resource) throws IOException {
        String path = "static/" + resource;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new NoSuchFileException(path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
