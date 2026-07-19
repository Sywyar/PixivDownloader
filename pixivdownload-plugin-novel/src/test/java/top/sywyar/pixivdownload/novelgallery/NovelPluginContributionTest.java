package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.novel.config.NovelExecutionSettings;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel 插件入口 contribution 声明")
class NovelPluginContributionTest {

    private final NovelPlugin plugin = new NovelPlugin();

    @Test
    @DisplayName("未发布插件描述符统一要求首个核心 API 1.0")
    void descriptorRequiresInitialApi10() throws Exception {
        Properties descriptor = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(input).isNotNull();
            descriptor.load(input);
        }
        assertThat(descriptor.getProperty("plugin.requires")).isEqualTo("1.0");
    }

    @Test
    @DisplayName("novel 同时声明小说下载核心路由和小说画廊展示路由")
    void routesIncludeDownloadAndDisplay() {
        assertThat(plugin.routes())
                .extracting(route -> route.pathPattern())
                .containsExactlyInAnyOrder(
                        "/api/novel/download",
                        "/api/novel/status/**",
                        "/api/novel/translate-status/**",
                        "/api/novel/*/downloaded",
                        "/api/novel/series/*/merge",
                        "/api/novel/series/*/merged",
                        "/api/novel/*/translate",
                        "/api/novel/translate-lang-probe",
                        "/api/novel/series/*/translate-title",
                        "/api/novel/series/*/novel-ids",
                        "/api/narration/**",
                        "/api/download/pixiv/novel",
                        "/api/download/novel/status/**",
                        "/api/download/novel/translate-status/**",
                        "/api/pixiv/novel/*/meta",
                        "/api/pixiv/novel/*/bookmark-count",
                        "/api/pixiv/novel/series/*",
                        "/api/pixiv/novel-search**",
                        "/api/pixiv/user/*/novels",
                        "/api/pixiv/user/*/novel-cards",
                        "/api/pixiv/me/novel-bookmarks",
                        "/pixiv-novel-download/**",
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/**",
                        "/pixiv-novel/**",
                        "/api/gallery/novel/**",
                        "/api/gallery/novels/**",
                        "/api/gallery/novels");
    }

    @Test
    @DisplayName("novel 下载 / 展示资源与 i18n namespace 由插件声明")
    void staticResourcesAndI18nAreOwnedByPlugin() {
        assertThat(plugin.staticResources())
                .extracting(StaticResourceSummary::from)
                .containsExactlyInAnyOrder(
                        new StaticResourceSummary("/pixiv-novel-download/", false),
                        new StaticResourceSummary("/pixiv-novel-gallery.html", true),
                        new StaticResourceSummary("/pixiv-novel.html", true),
                        new StaticResourceSummary("/pixiv-novel-gallery/", false),
                        new StaticResourceSummary("/pixiv-novel/", false));
        assertThat(plugin.i18n())
                .extracting(i18n -> i18n.namespace())
                .containsExactlyInAnyOrder("novel", "narration", "novel-gallery");
        assertThat(plugin.i18n())
                .filteredOn(i18n -> i18n.namespace().equals("novel-gallery"))
                .singleElement()
                .satisfies(i18n -> assertThat(i18n.baseName()).isEqualTo("i18n.web.novel-gallery"));
    }

    @Test
    @DisplayName("小说并发设置由 novel 向下载分组贡献并使用插件 i18n")
    void executionSettingsAreOwnedByPlugin() throws Exception {
        var contribution = plugin.guiConfigContributions().get(0);

        assertThat(contribution.groups()).isEmpty();
        assertThat(contribution.sections()).isEmpty();
        assertThat(contribution.fields())
                .extracting(field -> field.key())
                .containsExactly(
                        NovelExecutionSettings.DOWNLOAD_CONCURRENCY_KEY,
                        NovelExecutionSettings.TRANSLATION_CONCURRENCY_KEY);
        assertThat(contribution.fields()).allSatisfy(field -> {
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.DOWNLOAD);
            assertThat(field.i18nNamespace()).isEqualTo(NovelPlugin.ID);
            assertThat(field.type()).isEqualTo(GuiConfigFieldType.INT);
            assertThat(field.defaultValue()).isEqualTo("10");
            assertThat(field.minValue()).isEqualTo(1);
            assertThat(field.maxValue()).isNull();
            assertThat(field.sensitive()).isFalse();
            assertThat(field.requiresRestart()).isTrue();
        });

        Properties chinese = loadProperties("/i18n/web/novel.properties");
        Properties english = loadProperties("/i18n/web/novel_en.properties");
        assertThat(contribution.fields()).allSatisfy(field -> {
            assertThat(chinese).containsKeys(field.labelKey(), field.helpKey());
            assertThat(english).containsKeys(field.labelKey(), field.helpKey());
        });
    }

    @Test
    @DisplayName("novel 声明小说队列类型与下载页 UI 槽位")
    void queueTypeAndUiSlotsAreOwnedByPlugin() {
        assertThat(plugin.queueTypes())
                .singleElement()
                .satisfies(queueType -> {
                    assertThat(queueType.pluginId()).isEqualTo("novel");
                    assertThat(queueType.type()).isEqualTo("novel");
                    assertThat(queueType.moduleUrl()).isEqualTo("/pixiv-novel-download/novel-queue-type.js");
                });
        assertThat(plugin.uiSlots())
                .extracting(slot -> slot.target())
                .containsExactlyInAnyOrder(
                        "kind-option-user", "kind-option-search", "kind-option-quick",
                        "quick-actions-bookmarks", "quick-actions-mine",
                        "import-hint", "search-filter", "settings-card");
    }

    @Test
    @DisplayName("novel-gallery 导航入口和共享画廊类型切换入口由插件声明")
    void navigationIsOwnedByPlugin() {
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("novel-gallery"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactlyInAnyOrder(
                            NavigationPlacements.APP_TOP,
                            NavigationPlacements.NOVEL_SIDEBAR);
                    assertThat(nav.labelNamespace()).isEqualTo("novel-gallery");
                    assertThat(nav.href()).isEqualTo("/pixiv-novel-gallery.html?view=all");
                });
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("novel-type-switch"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(nav.labelNamespace()).isEqualTo("novel-gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("nav.type-novel");
                    assertThat(nav.href()).isEqualTo("/pixiv-novel-gallery.html?view=all");
                });
    }

    private record StaticResourceSummary(String publicPathPrefix, boolean exactPath) {
        private static StaticResourceSummary from(
                top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution contribution) {
            return new StaticResourceSummary(contribution.publicPathPrefix(), contribution.exactFile());
        }
    }

    private Properties loadProperties(String resource) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        return properties;
    }
}
