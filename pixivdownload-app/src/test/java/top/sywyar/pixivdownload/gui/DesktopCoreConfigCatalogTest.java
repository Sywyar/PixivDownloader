package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("桌面核心配置目录")
class DesktopCoreConfigCatalogTest {

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
}
