package top.sywyar.pixivdownload.gui.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.plugin.GuiPluginStatusModel.Outcome;
import top.sywyar.pixivdownload.gui.plugin.GuiPluginStatusModel.Row;
import top.sywyar.pixivdownload.gui.plugin.GuiPluginStatusModel.Source;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuiPluginStatusModel} 纯逻辑单测（无 Swing、可 headless 运行）：把 {@code /api/gui/plugins/status} 响应解析为
 * 展示模型——覆盖空列表、恢复模式、外置 stats、必选插件、未安装项、未知来源，以及离线 / 拒绝 / 错误 / 坏正文等错误提示分类，
 * 并验证来源 / 状态 / 阶段标签的本地化与未知码回退。
 */
@DisplayName("GuiPluginStatusModel GUI 插件状态展示模型")
class GuiPluginStatusModelTest {

    @Test
    @DisplayName("空列表：OK、非恢复模式、无展示行")
    void emptyList() {
        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(
                true, 200, "{\"recoveryMode\":false,\"plugins\":[]}");

        assertThat(model.outcome()).isEqualTo(Outcome.OK);
        assertThat(model.recoveryMode()).isFalse();
        assertThat(model.rows()).isEmpty();
    }

    @Test
    @DisplayName("恢复模式：recoveryMode=true 被解析为真")
    void recoveryMode() {
        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(
                true, 200, "{\"recoveryMode\":true,\"plugins\":[]}");

        assertThat(model.outcome()).isEqualTo(Outcome.OK);
        assertThat(model.recoveryMode()).isTrue();
    }

    @Test
    @DisplayName("外置 stats：来源 EXTERNAL、保留 id / 名称 / 状态 / 阶段 / 受管 / 版本")
    void externalStats() {
        String body = "{\"recoveryMode\":false,\"plugins\":[{"
                + "\"id\":\"stats\",\"name\":\"Statistics\",\"source\":\"external\","
                + "\"status\":\"STARTED\",\"runtimePhase\":\"STARTED\",\"managed\":true,"
                + "\"required\":false,\"version\":\"1.0.0\"}]}";

        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(true, 200, body);

        assertThat(model.outcome()).isEqualTo(Outcome.OK);
        assertThat(model.rows()).hasSize(1);
        Row row = model.rows().get(0);
        assertThat(row.id()).isEqualTo("stats");
        assertThat(row.name()).isEqualTo("Statistics");
        assertThat(row.source()).isEqualTo(Source.EXTERNAL);
        assertThat(row.statusCode()).isEqualTo("STARTED");
        assertThat(row.phaseCode()).isEqualTo("STARTED");
        assertThat(row.managed()).isTrue();
        assertThat(row.required()).isFalse();
        assertThat(row.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("必选插件：required=true 被保留")
    void requiredPlugin() {
        String body = "{\"recoveryMode\":false,\"plugins\":[{"
                + "\"id\":\"download-workbench\",\"name\":\"下载工作台\",\"source\":\"built-in\","
                + "\"status\":\"STARTED\",\"runtimePhase\":null,\"managed\":false,"
                + "\"required\":true,\"version\":\"0.0.1\"}]}";

        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(true, 200, body);

        Row row = model.rows().get(0);
        assertThat(row.required()).isTrue();
        assertThat(row.source()).isEqualTo(Source.BUILT_IN);
        assertThat(row.phaseCode()).isNull();
        assertThat(row.managed()).isFalse();
    }

    @Test
    @DisplayName("未安装的必选项：来源 NOT_INSTALLED、缺名称时回退到 id、版本可空")
    void notInstalledEntry() {
        String body = "{\"recoveryMode\":true,\"plugins\":[{"
                + "\"id\":\"download-workbench\",\"source\":\"not-installed\","
                + "\"status\":\"MISSING_REQUIRED\",\"managed\":false,\"required\":true}]}";

        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(true, 200, body);

        Row row = model.rows().get(0);
        assertThat(row.source()).isEqualTo(Source.NOT_INSTALLED);
        assertThat(row.name()).isEqualTo("download-workbench");
        assertThat(row.statusCode()).isEqualTo("MISSING_REQUIRED");
        assertThat(row.version()).isNull();
        assertThat(row.phaseCode()).isNull();
    }

    @Test
    @DisplayName("未知来源串归 Source.UNKNOWN")
    void unknownSource() {
        String body = "{\"recoveryMode\":false,\"plugins\":[{"
                + "\"id\":\"x\",\"name\":\"x\",\"source\":\"future-kind\",\"status\":\"STARTED\"}]}";

        GuiPluginStatusModel model = GuiPluginStatusModel.fromResponse(true, 200, body);

        assertThat(model.rows().get(0).source()).isEqualTo(Source.UNKNOWN);
    }

    @Test
    @DisplayName("错误提示：后端不可达 → OFFLINE")
    void offlineWhenUnreachable() {
        assertThat(GuiPluginStatusModel.fromResponse(false, 0, null).outcome()).isEqualTo(Outcome.OFFLINE);
        assertThat(GuiPluginStatusModel.offline().outcome()).isEqualTo(Outcome.OFFLINE);
    }

    @Test
    @DisplayName("错误提示：403 → FORBIDDEN")
    void forbiddenWhen403() {
        assertThat(GuiPluginStatusModel.fromResponse(true, 403, "{}").outcome()).isEqualTo(Outcome.FORBIDDEN);
    }

    @Test
    @DisplayName("错误提示：非 2xx → ERROR；坏正文 / 空正文 → ERROR")
    void errorWhenBadStatusOrBody() {
        assertThat(GuiPluginStatusModel.fromResponse(true, 500, "{}").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(GuiPluginStatusModel.fromResponse(true, 200, "not-json{").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(GuiPluginStatusModel.fromResponse(true, 200, "").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(GuiPluginStatusModel.fromResponse(true, 200, null).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    @DisplayName("标签本地化：已知来源 / 状态 / 阶段码解析为非空文案，未知码回退原始码，空码回退空串")
    void labelsLocalizeWithFallback() {
        assertThat(GuiPluginStatusModel.sourceLabel(Source.EXTERNAL)).isNotBlank();
        assertThat(GuiPluginStatusModel.sourceLabel(Source.BUILT_IN)).isNotBlank();

        assertThat(GuiPluginStatusModel.statusLabel("STARTED")).isNotBlank().isNotEqualTo("STARTED");
        assertThat(GuiPluginStatusModel.statusLabel("BOGUS")).isEqualTo("BOGUS");
        assertThat(GuiPluginStatusModel.statusLabel(null)).isEmpty();

        assertThat(GuiPluginStatusModel.phaseLabel("QUIESCED")).isNotBlank().isNotEqualTo("QUIESCED");
        assertThat(GuiPluginStatusModel.phaseLabel(null)).isEmpty();
    }
}
