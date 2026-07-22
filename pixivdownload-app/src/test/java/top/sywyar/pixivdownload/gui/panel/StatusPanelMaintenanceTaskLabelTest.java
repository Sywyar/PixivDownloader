package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("状态页维护任务标签测试")
class StatusPanelMaintenanceTaskLabelTest {

    @AfterEach
    void clearLocaleOverride() {
        GuiMessages.clearLocaleOverride();
    }

    @Test
    @DisplayName("宿主核心维护任务使用内置本地化标签")
    void coreMaintenanceTasksUseBuiltInLabels() {
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);

        assertThat(StatusPanel.maintenanceTaskLabel("database-optimize")).isEqualTo("数据库优化");
        assertThat(StatusPanel.maintenanceTaskLabel("guest-invite-cleanup")).isEqualTo("访客邀请清理");
    }

    @Test
    @DisplayName("外置维护任务通过通用模板展示稳定机器码")
    void externalMaintenanceTaskUsesGenericLocalizedTemplate() {
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertThat(StatusPanel.maintenanceTaskLabel("external-maintenance"))
                .isEqualTo("维护任务（external-maintenance）");

        GuiMessages.setLocale(Locale.US);
        assertThat(StatusPanel.maintenanceTaskLabel("external-maintenance"))
                .isEqualTo("Maintenance task (external-maintenance)");
    }
}
