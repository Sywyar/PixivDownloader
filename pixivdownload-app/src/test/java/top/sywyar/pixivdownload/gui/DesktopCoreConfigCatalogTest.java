package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面核心配置目录")
class DesktopCoreConfigCatalogTest {

    @Test
    @DisplayName("插件设置分组由插件快照提供名称")
    void leavesPluginSettingsGroupsToPluginSnapshots() {
        assertThat(DesktopCoreConfigCatalog.groups())
                .extracting(GuiConfigGroupContribution::groupId)
                .doesNotContain(GuiConfigGroups.AI, GuiConfigGroups.NOTIFICATION);
    }
}
