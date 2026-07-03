package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationMarkers;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 插件入口 contribution 声明")
class GalleryPluginContributionTest {

    private final GalleryPlugin plugin = new GalleryPlugin();

    @Test
    @DisplayName("gallery 默认启动落点由插件声明并绑定 solo 上下文")
    void startupRouteIsOwnedByGalleryPlugin() {
        assertThat(plugin.startupRoutes())
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.pluginId()).isEqualTo("gallery");
                    assertThat(route.path()).isEqualTo("/pixiv-gallery.html");
                    assertThat(route.preferredContexts()).containsExactly(StartupRouteContext.SOLO);
                });
    }

    @Test
    @DisplayName("gallery GUI Web 入口由导航 contribution 声明并使用 gallery i18n namespace")
    void guiWebEntryIsOwnedByGalleryPlugin() {
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery"))
                .singleElement()
                .satisfies(nav -> assertThat(nav.markers()).containsExactly(NavigationMarkers.FIRST_DOWNLOAD_RESULT));
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery-gui-open"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactlyInAnyOrder(
                            NavigationPlacements.GUI_STATUS_ACTIONS,
                            NavigationPlacements.GUI_TRAY_ACTIONS);
                    assertThat(nav.labelNamespace()).isEqualTo("gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("gui.action.open");
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html");
                });
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery-invite-manage-back"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactly(NavigationPlacements.INVITE_MANAGE_BACK);
                    assertThat(nav.labelNamespace()).isEqualTo("gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("invite.manage.back");
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html?view=all");
                });
    }

    @Test
    @DisplayName("gallery 欢迎页步骤由插件声明并使用 gallery i18n namespace")
    void onboardingStepIsOwnedByGalleryPlugin() {
        assertThat(plugin.guiOnboardingSteps())
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.pluginId()).isEqualTo("gallery");
                    assertThat(step.stepId()).isEqualTo("local-gallery-guide");
                    assertThat(step.i18nNamespace()).isEqualTo("gallery");
                    assertThat(step.titleKey()).isEqualTo("gui.onboarding.title");
                    assertThat(step.actionLabelKey()).isEqualTo("gui.onboarding.button");
                    assertThat(step.actionHref()).isEqualTo("/pixiv-gallery.html");
                    assertThat(step.completionKey()).isEqualTo("local-gallery-guide");
                });
    }

    @Test
    @DisplayName("gallery 声明的核心列使用均能在受管 schema 中找到对应表列")
    void coreColumnUsagesResolveAgainstManagedSchema() {
        ManagedDatabaseSchema.DatabaseSchema schema =
                DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema();
        for (CoreColumnUsage usage : plugin.coreColumnUsages()) {
            ManagedDatabaseSchema.TableSpec table = schema.tables().values().stream()
                    .filter(spec -> spec.name().equals(ManagedDatabaseSchema.normalizeIdentifier(usage.table())))
                    .findFirst()
                    .orElse(null);
            assertThat(table)
                    .as("gallery 声明的核心表 %s 应在受管 schema 中", usage.table())
                    .isNotNull();
            Set<String> columns = table.columns().stream()
                    .map(ManagedDatabaseSchema.ColumnSpec::name)
                    .collect(Collectors.toSet());
            for (String column : usage.columns()) {
                assertThat(columns)
                        .as("gallery 声明的核心列 %s.%s 应在受管 schema 中", usage.table(), column)
                        .contains(ManagedDatabaseSchema.normalizeIdentifier(column));
            }
        }
    }
}
