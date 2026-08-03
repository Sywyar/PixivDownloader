package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackStateRequest;
import top.sywyar.pixivdownload.download.response.LayoutFeedbackStateResponse;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateFiles;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("布局偏好调查服务端状态端点")
class LayoutFeedbackStateControllerTest {

    private static final String SOLO = "solo";
    private static final String MULTI = "multi";
    private static final String INSTALL_ID = "11111111-2222-4333-8444-555555555555";

    @TempDir
    Path tempDir;

    private LayoutFeedbackStateController controller(String mode, boolean preloaded) throws IOException {
        Path stateDir = tempDir.resolve("state/download-workbench");
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID))
                .thenReturn(stateDir);
        ApplicationModeProvider applicationModeProvider = mock(ApplicationModeProvider.class);
        when(applicationModeProvider.getMode()).thenReturn(mode);
        InstallIdentityProvider installIdentityProvider = mock(InstallIdentityProvider.class);
        when(installIdentityProvider.get()).thenReturn(INSTALL_ID);
        LayoutFeedbackStateController controller = new LayoutFeedbackStateController(
                new LayoutFeedbackStateFiles(runtimePathProvider),
                new ObjectMapper(),
                applicationModeProvider,
                installIdentityProvider);
        if (preloaded) {
            controller.loadState();
        }
        return controller;
    }

    @Test
    @DisplayName("solo 模式返回安装身份 distinct_id 与空状态")
    void soloReturnsIdentityAndEmptyState() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, false);

        ResponseEntity<LayoutFeedbackStateResponse> response = controller.getState();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().available()).isTrue();
        assertThat(response.getBody().distinctId()).isEqualTo(INSTALL_ID);
        assertThat(response.getBody().state()).isNull();
        assertThat(response.getBody().seen()).isNull();
    }

    @Test
    @DisplayName("multi 模式一律 403，前端回退 localStorage")
    void multiRejects() throws IOException {
        LayoutFeedbackStateController controller = controller(MULTI, false);

        assertThat(controller.getState().getStatusCode().value()).isEqualTo(403);
        assertThat(controller.saveState(new LayoutFeedbackStateRequest(null, null))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .isFalse();
    }

    @Test
    @DisplayName("保存后重新加载返回同一状态与 seen")
    void savedStateRoundTrips() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, false);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode state = objectMapper.readTree(
                "{\"surveyId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"status\":\"submitted\","
                        + "\"updatedAt\":1,\"snoozedUntil\":0}");
        JsonNode seen = objectMapper.readTree(
                "{\"pixiv-batch-landscape\":{\"firstSeenAt\":1,\"lastSeenAt\":1}}");

        ResponseEntity<Void> saved = controller.saveState(new LayoutFeedbackStateRequest(state, seen));

        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("submitted");

        LayoutFeedbackStateController reloaded = controller(SOLO, true);
        ResponseEntity<LayoutFeedbackStateResponse> response = reloaded.getState();
        assertThat(response.getBody().distinctId()).isEqualTo(INSTALL_ID);
        assertThat(response.getBody().state().path("status").asText()).isEqualTo("submitted");
        assertThat(response.getBody().seen().path("pixiv-batch-landscape").path("lastSeenAt").asLong())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("状态文件损坏时 GET 返回空状态而不抛错")
    void corruptStateFileDegradesToEmpty() throws IOException {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);

        ResponseEntity<LayoutFeedbackStateResponse> response = controller(SOLO, true).getState();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().state()).isNull();
        assertThat(response.getBody().seen()).isNull();
        assertThat(response.getBody().distinctId()).isEqualTo(INSTALL_ID);
    }
}
