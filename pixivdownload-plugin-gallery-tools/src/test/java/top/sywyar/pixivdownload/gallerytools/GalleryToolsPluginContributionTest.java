package top.sywyar.pixivdownload.gallerytools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery-tools 合并贡献")
class GalleryToolsPluginContributionTest {

    private static final List<String> PRIVATE_MESSAGE_KEYS = List.of(
            "duplicate.error.threshold.invalid",
            "duplicate.error.ahash-threshold.invalid",
            "duplicate.error.scope.invalid",
            "duplicate.log.scan.started",
            "duplicate.log.scan.done",
            "duplicate.log.scan.failed",
            "duplicate.log.backfill.done");

    @Test
    @DisplayName("单一插件声明统计与疑似重复的管理员路由、资源和 i18n")
    void declaresBothFeatureSurfaces() {
        GalleryToolsPlugin plugin = new GalleryToolsPlugin();

        assertThat(plugin.routes())
                .extracting(route -> route.pathPattern() + "|" + route.accessPolicy())
                .containsExactlyInAnyOrder(
                        "/pixiv-stats.html|" + AccessPolicy.ADMIN,
                        "/pixiv-stats/**|" + AccessPolicy.ADMIN,
                        "/api/stats/**|" + AccessPolicy.ADMIN,
                        "/pixiv-duplicates.html|" + AccessPolicy.ADMIN,
                        "/pixiv-duplicates/**|" + AccessPolicy.ADMIN,
                        "/api/duplicates/**|" + AccessPolicy.ADMIN);
        assertThat(plugin.staticResources())
                .extracting(resource -> resource.publicPathPrefix() + "|" + resource.exactFile())
                .containsExactlyInAnyOrder(
                        "/pixiv-stats.html|true", "/pixiv-stats/|false",
                        "/pixiv-duplicates.html|true", "/pixiv-duplicates/|false");
        assertThat(plugin.i18n())
                .extracting(bundle -> bundle.namespace() + "|" + bundle.baseName())
                .containsExactly(
                        "gallery-tools|i18n.web.gallery-tools",
                        "stats|i18n.web.stats",
                        "duplicates|i18n.web.duplicates");
    }

    @Test
    @DisplayName("统计与疑似重复只贡献下一级 Web 导航")
    void featureNavigationStaysOutOfAppTop() {
        GalleryToolsPlugin plugin = new GalleryToolsPlugin();

        assertThat(plugin.navigation()).filteredOn(nav -> nav.id().equals("stats")).singleElement()
                .satisfies(nav -> assertThat(nav.placements()).containsExactlyInAnyOrder(
                        NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR,
                        NavigationPlacements.NOVEL_SIDEBAR,
                        NavigationPlacements.DUPLICATES_HEADER_ICONS,
                        NavigationPlacements.DESKTOP_QUICK_START));
        assertThat(plugin.navigation()).filteredOn(nav -> nav.id().equals("duplicate")).singleElement()
                .satisfies(nav -> assertThat(nav.placements()).containsExactlyInAnyOrder(
                        NavigationPlacements.APP_SIDEBAR,
                        NavigationPlacements.GALLERY_SIDEBAR,
                        NavigationPlacements.NOVEL_SIDEBAR));
    }

    @Test
    @DisplayName("合并模块 classpath 同时携带统计与疑似重复资源")
    void mergedClasspathCarriesBothFeatureResources() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource("plugin.properties")).isNotNull();
        assertThat(loader.getResource("static/pixiv-stats.html")).isNotNull();
        assertThat(loader.getResource("static/pixiv-duplicates.html")).isNotNull();
        assertThat(loader.getResource("i18n/web/gallery-tools.properties")).isNotNull();
        assertThat(loader.getResource("i18n/web/stats.properties")).isNotNull();
        assertThat(loader.getResource("i18n/web/duplicates.properties")).isNotNull();
    }

    @Test
    @DisplayName("疑似重复中英文资源继续拥有业务错误与扫描维护日志文案")
    void ownsDuplicatePrivateBusinessMessages() throws IOException {
        Properties chinese = loadProperties("i18n/web/duplicates.properties");
        Properties english = loadProperties("i18n/web/duplicates_en.properties");

        assertThat(chinese.stringPropertyNames()).containsAll(PRIVATE_MESSAGE_KEYS);
        assertThat(english.stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(chinese.stringPropertyNames());
    }

    private static Properties loadProperties(String resource) throws IOException {
        Properties properties = new Properties();
        try (var input = GalleryToolsPluginContributionTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("gallery-tools 资源应存在：%s", resource).isNotNull();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
