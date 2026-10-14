package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("桌面核心配置目录")
class DesktopCoreConfigCatalogTest {

    @Test
    @DisplayName("连接字段与类型由提供者声明，空声明隐藏地址及凭据")
    void declaresDatabaseFieldsAndVisibility() {
        var host = mock(DesktopUiHost.class);
        var settings = mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.class);
        when(host.databaseSettings()).thenReturn(java.util.Optional.of(settings));
        when(settings.providers()).thenReturn(java.util.List.of(
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.ProviderChoice("fixture-file", "unknown-file-engine",
                        new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin().connectionFields()),
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.ProviderChoice("fixture-server", "unknown-server-engine",
                        new top.sywyar.pixivdownload.databasepostgresql.PostgresDatabasePlugin().connectionFields())));
        var fields = DesktopCoreConfigCatalog.fields(host).stream()
                .filter(field -> field.groupId().equals(GuiConfigGroups.DATABASE))
                .collect(java.util.stream.Collectors.toMap(field -> field.key(), field -> field));
        assertThat(fields.get("database.provider").enumValues()).containsExactly("fixture-file", "fixture-server");
        assertThat(fields).doesNotContainKey("database.engine");
        for (String name : java.util.List.of("provider", "jdbc-url", "username", "password")) {
            assertThat(fields.get("database." + name).effect()).isEqualTo(GuiConfigEffect.PROCESS_RESTART);
        }
        for (String name : java.util.List.of("jdbc-url", "username", "password")) {
            var conditions = fields.get("database." + name).visibleWhen();
            assertThat(conditions).isNotEmpty();
            assertThat(conditions.stream().allMatch(condition -> condition.matches(
                    java.util.Map.of("database.provider", "fixture-file")))).isFalse();
            assertThat(conditions.stream().allMatch(condition -> condition.matches(
                    java.util.Map.of("database.provider", "fixture-server")))).isTrue();
            assertThat(conditions.stream().allMatch(condition -> condition.matches(java.util.Map.of()))).isFalse();
        }
        assertThat(fields.get("database.password").sensitive()).isTrue();
        assertThat(fields.get("database.password").defaultValue()).isEmpty();
    }

    @Test
    @DisplayName("未知提供者可以只声明地址，未加载提供者时不合成连接字段")
    void declaresOnlyAvailableParameters(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var host = mock(DesktopUiHost.class);
        var provider = new top.sywyar.pixivdownload.plugin.storage.H2ContractProvider();
        var selected = new top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.Result(
                provider.id(), provider.databaseId(), java.util.Optional.of(provider),
                top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.Failure.NONE);
        var providers = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(selected));
        var settings = new top.sywyar.pixivdownload.gui.config.DatabaseSettingsService(
                new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(directory.resolve("config.yaml")).asConfigFile(),
                org.springframework.mock.env.MockEnvironment::new, java.util.Optional::empty, providers::get);
        when(host.databaseSettings()).thenReturn(java.util.Optional.of(settings));
        assertThat(settings.providers()).singleElement().satisfies(choice ->
                assertThat(choice.connectionFields()).isEqualTo(provider.connectionFields()));
        assertThat(DesktopCoreConfigCatalog.fields(host)).extracting(field -> field.key())
                .contains("database.jdbc-url").doesNotContain("database.username", "database.password");
        assertThat(settings.check(java.util.Map.of("database.provider", provider.id(), "database.jdbc-url",
                "contract:fields_" + java.util.UUID.randomUUID().toString().replace("-", ""))).outcome())
                .isEqualTo(top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings.Outcome.CONNECTED);
        providers.set(java.util.List.of());
        assertThat(DesktopCoreConfigCatalog.fields(host)).extracting(field -> field.key())
                .doesNotContain("database.jdbc-url", "database.username", "database.password");
    }

    @Test
    @DisplayName("下载设置显示受限的 FFmpeg 并发并声明后端重启")
    void exposesFfmpegConcurrencyInDownloadSettings() {
        assertThat(DesktopCoreConfigCatalog.fields(mock(DesktopUiHost.class)))
                .filteredOn(field -> field.key().equals("ffmpeg.max-concurrent"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.groupId()).isEqualTo(GuiConfigGroups.DOWNLOAD);
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.INT);
                    assertThat(field.effect()).isEqualTo(GuiConfigEffect.BACKEND_RESTART);
                    assertThat(Integer.parseInt(field.defaultValue())).isEqualTo(
                            new top.sywyar.pixivdownload.ffmpeg.FfmpegProperties().getMaxConcurrent());
                });
    }

    @Test
    @DisplayName("插件设置分组由插件快照提供名称")
    void leavesPluginSettingsGroupsToPluginSnapshots() {
        assertThat(DesktopCoreConfigCatalog.groups())
                .extracting(GuiConfigGroupContribution::groupId)
                .doesNotContain(GuiConfigGroups.AI, GuiConfigGroups.NOTIFICATION);
    }

    @Test
    @DisplayName("维护时间使用通用时间字段类型")
    void declaresMaintenanceTimesAsTimeFields() {
        DesktopUiHost host = mock(DesktopUiHost.class);
        when(host.defaultMaintenanceTime()).thenReturn("10:00");

        assertThat(DesktopCoreConfigCatalog.fields(host))
                .filteredOn(field -> field.key().startsWith("maintenance.") && field.key().endsWith(".time"))
                .hasSize(7)
                .allSatisfy(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.TIME);
                    assertThat(field.defaultValue()).isEqualTo("10:00");
                });
    }

    @Test
    @DisplayName("FFmpeg 自定义路径使用目录选择并在下次使用时生效")
    void declaresCustomFfmpegPathAsLiveDirectoryField() {
        DesktopUiHost host = mock(DesktopUiHost.class);

        assertThat(DesktopCoreConfigCatalog.fields(host))
                .filteredOn(field -> field.key().equals("ffmpeg.executable-path"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.PATH_DIR);
                    assertThat(field.defaultValue()).isEmpty();
                    assertThat(field.effect()).isEqualTo(GuiConfigEffect.HOT_RELOAD);
                });
    }
}
