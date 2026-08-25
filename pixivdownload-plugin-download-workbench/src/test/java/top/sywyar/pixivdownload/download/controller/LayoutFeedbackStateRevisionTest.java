package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateFiles;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("布局偏好调查修订与降级")
class LayoutFeedbackStateRevisionTest extends LayoutFeedbackStateControllerTestSupport {
    /* ============================================================
       无 CAS：合法命令一律 200；no-op 200 且 revision 不变
    ============================================================ */

    @Test
    @DisplayName("正常响应 revision 是 JavaScript 安全整数")
    void responseRevisionIsSafeInteger() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    @DisplayName("revision 耗尽且命令是 no-op：200 且 revision 不变")
    void revisionExhaustedNoOpReturns200() throws Exception {
        Path file = stateFilePath();
        writeV2Document(file, LayoutFeedbackStateStore.MAX_SAFE_REVISION,
                "{\"" + SURVEY_ID + "\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"submitted\",\"updatedAt\":1,\"snoozedUntil\":0}}");
        LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(stateDir()));

        MvcResult result = mockMvc(SOLO, store, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("耗尽时 no-op 响应必须原样返回 MAX_SAFE_REVISION")
                .contains("\"revision\":" + LayoutFeedbackStateStore.MAX_SAFE_REVISION);
    }

    @Test
    @DisplayName("revision 耗尽且命令需要真实修改：503 且 no-store, private，文件与内存不变")
    void revisionExhaustedRealChangeReturns503() throws Exception {
        Path file = stateFilePath();
        writeV2Document(file, LayoutFeedbackStateStore.MAX_SAFE_REVISION, "{}");
        LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(stateDir()));
        String before = Files.readString(file, StandardCharsets.UTF_8);

        mockMvc(SOLO, store, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        assertThat(store.snapshot().revision())
                .as("内存快照 revision 不变")
                .isEqualTo(LayoutFeedbackStateStore.MAX_SAFE_REVISION);
        assertThat(store.snapshot().seen()).as("内存快照 seen 不变").isEmpty();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("状态文件不变")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("状态文件 revision 超过上限：按损坏文件隔离，GET 返回安全空状态视图")
    void overLimitRevisionFileQuarantinedAndServesSafeEmptyView() throws Exception {
        Path file = stateFilePath();
        writeV2Document(file, LayoutFeedbackStateStore.MAX_SAFE_REVISION + 1L,
                "{\"" + SURVEY_ID + "\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"submitted\",\"updatedAt\":1,\"snoozedUntil\":0}}");
        LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(stateDir()));

        mockMvc(SOLO, store, null).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(true))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.canShow").value(true))
                .andExpect(jsonPath("$.retryAfterMs").value(0));

        assertThat(Files.exists(file)).as("超限 revision 文件必须被隔离").isFalse();
        try (var stream = Files.list(file.getParent())) {
            assertThat(stream.filter(p -> p.getFileName().toString().contains(".corrupt-")))
                    .as("超限 revision 文件按损坏隔离")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("合法动作不再返回 409（重复 submitted 是幂等 no-op，200 且 revision 不变）")
    void legalCommandsNeverReturn409() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("submitted"));
    }

    @Test
    @DisplayName("变化返回 200 且 revision +1；no-op 200 且 revision 不变")
    void revisionIncrementsOnChangeOnly() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    @DisplayName("旧 Survey 的 GET 返回 status=null / canShow=true，seenLayouts 继续返回")
    void otherSurveyStateIsNullButSeenKept() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isOk());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk());

        mockMvc.perform(get(ENDPOINT).param("surveyId", OTHER_SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.canShow").value(true))
                .andExpect(jsonPath("$.seenLayouts[0]").value("pixiv-batch-landscape"))
                .andExpect(jsonPath("$.revision").value(2));
    }

    /* ============================================================
       degraded
    ============================================================ */

    @Test
    @DisplayName("store degraded：GET 仍返回 scoped 身份且 stateAvailable=false、canShow=false，POST 503")
    void degradedStoreStillServesIdentityButRejectsWrites() throws Exception {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        Files.createDirectory(file);
        LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(stateDir()));
        MockMvc mockMvc = mockMvc(SOLO, store, null);

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(false))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value(0))
                .andExpect(jsonPath("$.distinctId").value(
                        org.hamcrest.Matchers.matchesPattern("^plf_[0-9a-f]{64}$")));

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isServiceUnavailable());
    }

}
