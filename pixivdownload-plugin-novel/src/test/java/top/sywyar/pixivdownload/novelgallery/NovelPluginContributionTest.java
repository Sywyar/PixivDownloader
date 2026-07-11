package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.novel.NovelPlugin;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel 插件入口 contribution 声明")
class NovelPluginContributionTest {

    private final NovelPlugin plugin = new NovelPlugin();

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
                        "/api/pixiv/novel/**",
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

    @Test
    @DisplayName("novel 声明的核心列使用均能在受管 schema 中找到对应表列")
    void coreColumnUsagesResolveAgainstManagedSchema() {
        ManagedDatabaseSchema.DatabaseSchema schema =
                DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        for (CoreColumnUsage usage : plugin.coreColumnUsages()) {
            ManagedDatabaseSchema.TableSpec table = schema.tables().values().stream()
                    .filter(spec -> spec.name().equals(ManagedDatabaseSchema.normalizeIdentifier(usage.table())))
                    .findFirst()
                    .orElse(null);
            assertThat(table)
                    .as("novel 声明的核心表 %s 应在受管 schema 中", usage.table())
                    .isNotNull();
            Set<String> columns = table.columns().stream()
                    .map(ManagedDatabaseSchema.ColumnSpec::name)
                    .collect(Collectors.toSet());
            for (String column : usage.columns()) {
                assertThat(columns)
                        .as("novel 声明的核心列 %s.%s 应在受管 schema 中", usage.table(), column)
                        .contains(ManagedDatabaseSchema.normalizeIdentifier(column));
            }
        }
    }

    private record StaticResourceSummary(String publicPathPrefix, boolean exactPath) {
        private static StaticResourceSummary from(
                top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution contribution) {
            return new StaticResourceSummary(contribution.publicPathPrefix(), contribution.exactFile());
        }
    }
}
