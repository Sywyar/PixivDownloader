package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 入口宿主边界守卫")
class GalleryEntryBoundaryGuardTest {

    private static final List<String> FORBIDDEN_ENTRY_TOKENS = List.of(
            "pixiv-gallery.html",
            "pixiv-douyin-gallery.html",
            "pixiv-douyin.html",
            "gallery-guide-done",
            "galleryGuideCompleted",
            "galleryVisited",
            "gui.welcome.gallery",
            "gui.action.open-gallery",
            "getGalleryUrl",
            "STEP_GALLERY",
            "GALLERY_PAGE");

    @Test
    @DisplayName("app 核心生产代码不得硬编码 gallery 页面或专用 GUI/onboarding 入口")
    void coreProductionCodeDoesNotHardcodeGalleryEntries() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(GalleryEntryBoundaryGuardTest::isScannedFile)
                        .filter(path -> !isPluginOwnedGalleryFile(path))
                        .forEach(path -> scan(path, violations));
            }
        }

        assertThat(violations).isEmpty();
    }

    private static boolean isScannedFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".properties")
                || name.endsWith(".html");
    }

    private static boolean isPluginOwnedGalleryFile(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/top/sywyar/pixivdownload/gallery/")
                || normalized.contains("/static/pixiv-gallery/")
                || normalized.contains("/static/pixiv-artwork/")
                || normalized.contains("/static/pixiv-showcase/")
                || normalized.contains("/static/pixiv-series/")
                || normalized.endsWith("/static/pixiv-gallery.html")
                || normalized.endsWith("/static/pixiv-artwork.html")
                || normalized.endsWith("/static/pixiv-showcase.html")
                || normalized.endsWith("/static/pixiv-series.html")
                || normalized.contains("/i18n/web/gallery");
    }

    private static void scan(Path path, List<String> violations) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN_ENTRY_TOKENS) {
                if (text.contains(token)) {
                    violations.add(path + " contains " + token);
                }
            }
        } catch (IOException e) {
            violations.add(path + " could not be read: " + e.getMessage());
        }
    }
}
