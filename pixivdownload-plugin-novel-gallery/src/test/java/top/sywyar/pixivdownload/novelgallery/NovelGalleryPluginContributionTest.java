package top.sywyar.pixivdownload.novelgallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("novel-gallery 插件入口 contribution 声明")
class NovelGalleryPluginContributionTest {

    private final NovelGalleryPlugin plugin = new NovelGalleryPlugin();

    @Test
    @DisplayName("novel-gallery 只声明小说画廊展示路由，不声明小说下载路由")
    void routesAreDisplayOnly() {
        assertThat(plugin.routes())
                .extracting(route -> route.pathPattern())
                .containsExactlyInAnyOrder(
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/**",
                        "/pixiv-novel/**",
                        "/api/gallery/novel/**",
                        "/api/gallery/novels/**",
                        "/api/gallery/novels")
                .doesNotContain("/api/novel/download", "/pixiv-novel-download/**");
    }

    @Test
    @DisplayName("novel-gallery 页面资源与 i18n namespace 由插件声明")
    void staticResourcesAndI18nAreOwnedByPlugin() {
        assertThat(plugin.staticResources())
                .extracting(StaticResourceSummary::from)
                .containsExactlyInAnyOrder(
                        new StaticResourceSummary("/pixiv-novel-gallery.html", true),
                        new StaticResourceSummary("/pixiv-novel.html", true),
                        new StaticResourceSummary("/pixiv-novel-gallery/", false),
                        new StaticResourceSummary("/pixiv-novel/", false));
        assertThat(plugin.i18n())
                .singleElement()
                .satisfies(i18n -> {
                    assertThat(i18n.namespace()).isEqualTo("novel-gallery");
                    assertThat(i18n.baseName()).isEqualTo("i18n.web.novel-gallery");
                });
    }

    @Test
    @DisplayName("novel-gallery 导航入口和画廊类型切换入口由插件声明")
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
                });
    }

    @Test
    @DisplayName("novel-gallery 声明的核心列使用均能在受管 schema 中找到对应表列")
    void coreColumnUsagesResolveAgainstManagedSchema() {
        ManagedDatabaseSchema.DatabaseSchema schema =
                DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        for (CoreColumnUsage usage : plugin.coreColumnUsages()) {
            ManagedDatabaseSchema.TableSpec table = schema.tables().values().stream()
                    .filter(spec -> spec.name().equals(ManagedDatabaseSchema.normalizeIdentifier(usage.table())))
                    .findFirst()
                    .orElse(null);
            assertThat(table)
                    .as("novel-gallery 声明的核心表 %s 应在受管 schema 中", usage.table())
                    .isNotNull();
            Set<String> columns = table.columns().stream()
                    .map(ManagedDatabaseSchema.ColumnSpec::name)
                    .collect(Collectors.toSet());
            for (String column : usage.columns()) {
                assertThat(columns)
                        .as("novel-gallery 声明的核心列 %s.%s 应在受管 schema 中", usage.table(), column)
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
