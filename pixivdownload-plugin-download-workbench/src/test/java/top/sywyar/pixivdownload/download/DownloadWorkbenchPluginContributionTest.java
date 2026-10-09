package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import top.sywyar.pixivdownload.download.media.UgoiraEncoderSettings;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("下载工作台静态声明")
class DownloadWorkbenchPluginContributionTest {
    @Test
    @DisplayName("路由与导航声明可复用且调用方不能修改")
    void reusesImmutableDeclarations() {
        var plugin = new DownloadWorkbenchPlugin();
        assertSame(plugin.routes(), plugin.routes());
        assertSame(plugin.navigation(), plugin.navigation());
        assertThrows(UnsupportedOperationException.class, () -> plugin.routes().clear());
    }

    @Test
    @DisplayName("后端编码字段只贡献 GUI 资源配置，默认值和保存后的绑定一致")
    void encoderSettingsBelongToDesktopBackendConfiguration() {
        var plugin = new DownloadWorkbenchPlugin();
        var fields = plugin.guiConfigContributions().stream().flatMap(config -> config.fields().stream()).toList();
        assertEquals(Set.of("download-workbench.ugoira.parallelism", "download-workbench.ugoira.lossless-effort",
                        "download-workbench.ugoira.max-output-mib", "download-workbench.ugoira.temporary-budget-gib",
                        "download-workbench.ugoira.timeout-minutes"),
                fields.stream().map(field -> field.key()).collect(Collectors.toSet()));
        var defaults = new UgoiraEncoderSettings();
        var values = fields.stream().collect(Collectors.toMap(field -> field.key(), field -> field.defaultValue()));
        var bound = bind(values);
        assertEquals(defaults.getParallelism(), bound.getParallelism());
        assertEquals(defaults.getLosslessEffort(), bound.getLosslessEffort());
        assertEquals(defaults.getMaxOutputMib(), bound.getMaxOutputMib());
        assertEquals(defaults.getTemporaryBudgetGib(), bound.getTemporaryBudgetGib());
        assertEquals(defaults.getTimeoutMinutes(), bound.getTimeoutMinutes());
        for (var field : fields) {
            assertEquals(GuiConfigGroups.DOWNLOAD, field.groupId());
            assertEquals(GuiConfigEffect.BACKEND_RESTART, field.effect());
            assertEquals("batch", field.i18nNamespace());
            assertNotNull(field.minValue());
            assertNotNull(field.maxValue());
            for (String invalid : new String[]{"-1", "1.5", "text", Long.toString(field.maxValue().longValue() + 1)}) {
                assertThrows(Exception.class, () -> bind(Map.of(field.key(), invalid)));
            }
            assertDoesNotThrow(() -> bind(Map.of(field.key(), field.maxValue().toString())));
            assertDoesNotThrow(() -> bind(Map.of(field.key(), field.minValue().toString())));
        }
        var custom = bind(Map.of("download-workbench.ugoira.parallelism", "6",
                "download-workbench.ugoira.lossless-effort", "100"));
        assertEquals(6, custom.getParallelism());
        assertEquals(100, custom.getLosslessEffort());
        var budgets = bind(Map.of("download-workbench.ugoira.max-output-mib", "768",
                "download-workbench.ugoira.temporary-budget-gib", "4", "download-workbench.ugoira.timeout-minutes", "60"));
        assertEquals(768, budgets.getMaxOutputMib());
        assertEquals(4, budgets.getTemporaryBudgetGib());
        assertEquals(60, budgets.getTimeoutMinutes());
        for (String invalid : new String[]{"0", "9", "1.5", "text"}) {
            assertThrows(Exception.class, () -> bind(Map.of("download-workbench.ugoira.parallelism", invalid)));
        }
        for (String invalid : new String[]{"-1", "101", "1.5"}) {
            assertThrows(Exception.class, () -> bind(Map.of("download-workbench.ugoira.lossless-effort", invalid)));
        }
    }

    private static UgoiraEncoderSettings bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind(UgoiraEncoderSettings.PREFIX, Bindable.of(UgoiraEncoderSettings.class)).get();
    }
}
