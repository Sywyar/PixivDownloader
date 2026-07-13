package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划任务前端状态与 pending 操作契约")
class ScheduleUiContractTest {

    private static final String RESOURCE = "static/pixiv-batch/modes/schedule.js";

    @Test
    @DisplayName("四类运行阻断原因有独立状态灯且只参与立即运行禁用")
    void operationalSuspensionsHaveDistinctLightsWithoutBlockingEditOrDelete() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("t.suspendReason === 'SOURCE_UNAVAILABLE'")
                .contains("t.suspendReason === 'EXECUTOR_UNAVAILABLE'")
                .contains("t.suspendReason === 'QUIESCED'")
                .contains("t.suspendReason === 'MIGRATION_ERROR'")
                .contains("code === 'SOURCE_UNAVAILABLE'")
                .contains("code === 'EXECUTOR_UNAVAILABLE'")
                .contains("code === 'QUIESCED'")
                .contains("code === 'MIGRATION_ERROR'")
                .contains("const suspended = !!t.suspendReason")
                .contains("const automaticSuspension = ['SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE', 'QUIESCED']")
                .contains("const manualRecoveryRequired = suspended && !automaticSuspension")
                .contains("schedule.disabled.run-capability")
                .contains("schedule.meta.next-capability")
                .contains("const runAttr = (t.enabled && !busy && !suspended)")
                .contains("${busyAttr} onclick=\"startEditScheduleTask(${t.id})\"")
                .contains("${busyAttr} onclick=\"deleteScheduleTask(${t.id})\"");
    }

    @Test
    @DisplayName("pending 清除以 data 绑定和 JSON 正文传递不透明复合身份")
    void pendingClearUsesDataBindingAndJsonBody() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("data-schedule-pending-clear")
                .contains("button.dataset.workType, button.dataset.workId")
                .contains("const workType = scheduleKindLabel(p.workType)")
                .contains("{workType, workId: p.workId, attempts: p.attempts, manual}")
                .contains("typeof detail.legacyReason === 'string'")
                .contains("headers: {'Content-Type': 'application/json'}")
                .contains("body: JSON.stringify({workType, workId})")
                .doesNotContain("onclick=\"clearPendingItem(")
                .doesNotContain("/pending/${encodeURIComponent(");
    }

    @Test
    @DisplayName("持久化中断结果直接驱动重新排期状态灯")
    void interruptedOutcomeDrivesRecoveryLight() throws IOException {
        String source = readSource();

        assertThat(source)
                .contains("t.lastOutcome === 'INTERRUPTED'")
                .doesNotContain("if (t.runStartedTime != null)");
    }

    private static String readSource() throws IOException {
        try (InputStream input = ScheduleUiContractTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertThat(input).as("测试 classpath 缺少 " + RESOURCE).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
