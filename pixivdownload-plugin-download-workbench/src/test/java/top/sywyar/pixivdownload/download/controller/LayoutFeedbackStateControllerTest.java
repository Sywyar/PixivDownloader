package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateFiles;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("布局偏好调查服务端状态端点")
class LayoutFeedbackStateControllerTest {

    private static final String SOLO = "solo";
    private static final String MULTI = "multi";
    private static final String INSTALL_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private LayoutFeedbackStateStore store() {
        return new LayoutFeedbackStateStore(new LayoutFeedbackStateFiles(stateDir()));
    }

    private RuntimePathProvider stateDir() {
        Path stateDir = tempDir.resolve("state/download-workbench");
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID))
                .thenReturn(stateDir);
        return runtimePathProvider;
    }

    private LayoutFeedbackStateController controller(String mode, LayoutFeedbackStateStore store,
                                                     InstallIdentityProvider identityProvider) {
        ApplicationModeProvider applicationModeProvider = mock(ApplicationModeProvider.class);
        when(applicationModeProvider.getMode()).thenReturn(mode);
        return new LayoutFeedbackStateController(
                store, applicationModeProvider,
                identityProvider == null ? () -> INSTALL_ID : identityProvider);
    }

    private byte[] commandBody(String command, long expectedRevision, List<String> layoutIds)
            throws IOException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedRevision", expectedRevision);
        body.put("surveyId", SURVEY_ID);
        body.put("command", command);
        if (layoutIds != null) {
            body.put("layoutIds", layoutIds);
        }
        return MAPPER.writeValueAsBytes(body);
    }

    private byte[] commandBody(String command, long expectedRevision) throws IOException {
        return commandBody(command, expectedRevision, null);
    }

    private byte[] commandBody(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /* ============================================================
       A. 身份不暴露
    ============================================================ */

    @Test
    @DisplayName("GET 响应返回 plf_ 前缀 scoped ID，绝不返回原始安装 UUID")
    void responseNeverExposesRawInstallUuid() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        ResponseEntity<LayoutFeedbackStateResponse> response =
                controller.getState(SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String distinctId = response.getBody().distinctId();
        assertThat(distinctId).matches("^plf_[0-9a-f]{64}$");
        assertThat(distinctId).doesNotContain(INSTALL_ID);
        assertThat(distinctId).doesNotContain("11111111");
    }

    @Test
    @DisplayName("相同安装 + 相同 surveyId 的 scoped ID 稳定一致")
    void scopedIdentityIsStable() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        String first = controller.getState(SURVEY_ID).getBody().distinctId();
        String second = controller.getState(SURVEY_ID).getBody().distinctId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("不同 surveyId 得到不同 scoped ID")
    void differentSurveyIdYieldsDifferentScopedId() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.getState(SURVEY_ID).getBody().distinctId())
                .isNotEqualTo(controller.getState("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff")
                        .getBody().distinctId());
    }

    /* ============================================================
       H. 模式
    ============================================================ */

    @Test
    @DisplayName("solo GET 成功")
    void soloGetSucceeds() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        ResponseEntity<LayoutFeedbackStateResponse> response = controller.getState(SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().available()).isTrue();
        assertThat(response.getBody().stateAvailable()).isTrue();
        assertThat(response.getBody().revision()).isZero();
        assertThat(response.getBody().state()).isNull();
        assertThat(response.getBody().seen()).isEmpty();
    }

    @Test
    @DisplayName("solo POST 成功并返回新快照")
    void soloPostSucceeds() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        ResponseEntity<LayoutFeedbackStateResponse> response =
                controller.saveState(commandBody("submitted", 0), SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().revision()).isEqualTo(1);
        assertThat(response.getBody().state().status().wireName()).isEqualTo("submitted");
    }

    @Test
    @DisplayName("multi GET / POST 一律 403，不调用 InstallIdentityProvider、不写状态文件")
    void multiRejectsEverything() throws IOException {
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        LayoutFeedbackStateController controller = controller(MULTI, store(), identityProvider);

        assertThat(controller.getState(SURVEY_ID).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.saveState(commandBody("submitted", 0), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(403);

        verify(identityProvider, never()).get();
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("multi 模式不得创建状态文件")
                .isFalse();
    }

    /* ============================================================
       G. 输入校验
    ============================================================ */

    @Test
    @DisplayName("unknown command → 400")
    void unknownCommandRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody("explode", 0), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("unknown JSON field → 400")
    void unknownJsonFieldRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody(
                "{\"expectedRevision\":0,\"surveyId\":\"" + SURVEY_ID
                        + "\",\"command\":\"never\",\"suggestion\":\"evil\"}"), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("非 JSON → 400")
    void nonJsonRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody("this is not json"), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("negative revision → 400")
    void negativeRevisionRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody("never", -1), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("缺 surveyId → 400")
    void missingSurveyIdRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody("never", 0), null)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.getState(null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("非法 surveyId → 400（GET / POST）")
    void invalidSurveyIdRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.getState("bad survey id").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.saveState(commandBody("never", 0), "bad survey id")
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("record_seen 无 layoutIds → 400")
    void recordSeenWithoutLayoutIdsRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(commandBody("record_seen", 0), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("state 命令携带 layoutIds → 400")
    void stateCommandWithLayoutIdsRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);

        assertThat(controller.saveState(
                commandBody("submitted", 0, List.of("pixiv-batch-landscape")), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("请求体超限 → 413，不解析、不修改文件")
    void oversizedBodyRejected() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);
        byte[] oversized = new byte[LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES + 1];

        assertThat(controller.saveState(oversized, SURVEY_ID).getStatusCode().value()).isEqualTo(413);
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .isFalse();
    }

    /* ============================================================
       CAS / 409
    ============================================================ */

    @Test
    @DisplayName("stale revision → 409 并携带当前完整快照")
    void staleRevisionReturns409WithSnapshot() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        controller.saveState(commandBody("submitted", 0), SURVEY_ID);

        ResponseEntity<LayoutFeedbackStateResponse> conflict =
                controller.saveState(commandBody("never", 0), SURVEY_ID);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().revision()).isEqualTo(1);
        assertThat(conflict.getBody().state().status().wireName()).isEqualTo("submitted");
    }

    @Test
    @DisplayName("409 不修改状态文件")
    void conflictDoesNotWriteFile() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        controller.saveState(commandBody("submitted", 0), SURVEY_ID);
        String before = Files.readString(
                tempDir.resolve("state/download-workbench/layout-feedback-state.json"),
                StandardCharsets.UTF_8);

        controller.saveState(commandBody("never", 0), SURVEY_ID);

        assertThat(Files.readString(
                tempDir.resolve("state/download-workbench/layout-feedback-state.json"),
                StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    @DisplayName("旧 Survey state 的 GET 返回 state=null，seen 继续返回")
    void otherSurveyStateIsNullButSeenKept() throws IOException {
        LayoutFeedbackStateStore store = store();
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        controller.saveState(commandBody("record_seen", 0, List.of("pixiv-batch-landscape")), SURVEY_ID);
        controller.saveState(commandBody("submitted", 1), SURVEY_ID);

        ResponseEntity<LayoutFeedbackStateResponse> other =
                controller.getState("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff");

        assertThat(other.getBody().state()).isNull();
        assertThat(other.getBody().seen().keySet()).containsExactly("pixiv-batch-landscape");
        assertThat(other.getBody().revision()).isEqualTo(2);
    }

    /* ============================================================
       degraded
    ============================================================ */

    @Test
    @DisplayName("store degraded：GET 仍返回 scoped 身份且 stateAvailable=false，POST 503")
    void degradedStoreStillServesIdentityButRejectsWrites() throws IOException {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        Files.createDirectory(file);
        LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(stateDir()));
        LayoutFeedbackStateController controller = controller(SOLO, store, null);

        ResponseEntity<LayoutFeedbackStateResponse> get = controller.getState(SURVEY_ID);
        assertThat(get.getStatusCode().value()).isEqualTo(200);
        assertThat(get.getBody().available()).isTrue();
        assertThat(get.getBody().stateAvailable()).isFalse();
        assertThat(get.getBody().distinctId()).matches("^plf_[0-9a-f]{64}$");

        assertThat(controller.saveState(commandBody("submitted", 0), SURVEY_ID)
                .getStatusCode().value()).isEqualTo(503);
    }
}
