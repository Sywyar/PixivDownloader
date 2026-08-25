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

@DisplayName("布局偏好调查身份、视图与模式端点")
class LayoutFeedbackStateControllerTest extends LayoutFeedbackStateControllerTestSupport {
    /* ============================================================
       A. 身份不暴露
    ============================================================ */

    @Test
    @DisplayName("GET 响应返回 plf_ 前缀 scoped ID，绝不返回原始安装 UUID")
    void responseNeverExposesRawInstallUuid() throws Exception {
        MvcResult result = mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distinctId").value(
                        org.hamcrest.Matchers.matchesPattern("^plf_[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.submissionId").value(
                        org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(INSTALL_ID).doesNotContain("11111111");
    }

    @Test
    @DisplayName("相同安装 + 相同 surveyId 的 scoped ID 稳定一致")
    void scopedIdentityIsStable() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);

        String first = distinctIdOf(mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk()).andReturn());
        String second = distinctIdOf(mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk()).andReturn());

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("不同 surveyId 得到不同 scoped ID")
    void differentSurveyIdYieldsDifferentScopedId() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);

        String first = distinctIdOf(mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk()).andReturn());
        String other = distinctIdOf(mockMvc.perform(get(ENDPOINT).param("surveyId", OTHER_SURVEY_ID))
                .andExpect(status().isOk()).andReturn());

        assertThat(first).isNotEqualTo(other);
    }

    /* ============================================================
       权威展示视图（status / canShow / retryAfterMs / seenLayouts）
    ============================================================ */

    @Test
    @DisplayName("GET null state：status=null，canShow=true，retryAfterMs=0")
    void getNullStateShowsAuthoritativeView() throws Exception {
        mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(true))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.canShow").value(true))
                .andExpect(jsonPath("$.retryAfterMs").value(0))
                .andExpect(jsonPath("$.seenLayouts").isArray());
    }

    @Test
    @DisplayName("submitted：canShow=false，retryAfterMs=0")
    void submittedViewIsAuthoritative() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value(0));
    }

    @Test
    @DisplayName("never：canShow=false，retryAfterMs=0")
    void neverViewIsAuthoritative() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("never"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value(0));
    }

    @Test
    @DisplayName("active snooze：canShow=false，retryAfterMs 正确")
    void activeSnoozeViewHasRetryAfter() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_786_000_000_000L), ZoneOffset.UTC);
        LayoutFeedbackStateController controller = controller(SOLO, store(), null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("snooze")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snoozed"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value((7L * 24 * 60 * 60 * 1000)));
    }

    @Test
    @DisplayName("expired snooze：canShow=true，retryAfterMs=0（服务端独立判断到期）")
    void expiredSnoozeViewCanShow() throws Exception {
        // 固定时钟：snooze 已过 7 天（snoozedUntil = NOW+7天 < NOW+8天）。
        long base = 1_786_000_000_000L;
        Clock clock = Clock.fixed(Instant.ofEpochMilli(base), ZoneOffset.UTC);
        LayoutFeedbackStateController controller = controller(SOLO, store(), null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("snooze")))
                .andExpect(status().isOk());

        LayoutFeedbackStateController expiredController =
                controller(SOLO, store(), null, Clock.fixed(
                        Instant.ofEpochMilli(base + (7L * 24 * 60 * 60 * 1000) + 1000),
                        ZoneOffset.UTC));
        MockMvc expiredMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(expiredController)
                .build();

        expiredMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snoozed"))
                .andExpect(jsonPath("$.canShow").value(true))
                .andExpect(jsonPath("$.retryAfterMs").value(0));
    }

    @Test
    @DisplayName("GET / POST 响应不含 serverTime / snoozedUntil / updatedAt / firstSeenAt / lastSeenAt")
    void responsesNeverExposeServerAbsoluteTime() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("snooze")))
                .andExpect(status().isOk());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isOk());

        MvcResult postResult = mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult getResult = mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andReturn();

        String combined = postResult.getResponse().getContentAsString(StandardCharsets.UTF_8)
                + getResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(combined)
                .as("响应不得包含服务端绝对时间点")
                .doesNotContain("serverTime")
                .doesNotContain("snoozedUntil")
                .doesNotContain("updatedAt")
                .doesNotContain("firstSeenAt")
                .doesNotContain("lastSeenAt");
    }

    @Test
    @DisplayName("seenLayouts 固定顺序且无重复（landscape / portrait / alt）")
    void seenLayoutsInFixedOrder() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        // 乱序 record_seen：先 alt，再 portrait，最后 landscape。
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID, List.of("pixiv-batch-alt"))))
                .andExpect(status().isOk());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", SURVEY_ID,
                                List.of("pixiv-batch-portrait", "pixiv-batch-landscape"))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body.indexOf("pixiv-batch-landscape"))
                .as("landscape 必须在 portrait 之前")
                .isLessThan(body.indexOf("pixiv-batch-portrait"));
        assertThat(body.indexOf("pixiv-batch-portrait"))
                .as("portrait 必须在 alt 之前")
                .isLessThan(body.indexOf("pixiv-batch-alt"));
    }

    /* ============================================================
       H. 模式
    ============================================================ */

    @Test
    @DisplayName("solo GET 成功")
    void soloGetSucceeds() throws Exception {
        mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(true))
                .andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    @DisplayName("multi GET 只返回稳定身份与提交 UUID，POST 仍在读取 body 前拒绝且不触发 Store 加载")
    void multiReturnsIdentityWithoutLoadingState() throws Exception {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        when(identityProvider.get()).thenReturn(INSTALL_ID);
        MockMvc mockMvc = mockMvc(MULTI, store(), identityProvider);

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(false))
                .andExpect(jsonPath("$.distinctId").value(
                        org.hamcrest.Matchers.matchesPattern("^plf_[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.submissionId").value(
                        org.hamcrest.Matchers.matchesPattern(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value(0));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isForbidden());

        verify(identityProvider).get();
        assertThat(Files.exists(file))
                .as("multi 模式不得读取或隔离状态文件")
                .isTrue();
        assertThat(corruptFiles(file)).as("multi 模式不得隔离损坏文件").isEmpty();
    }

    @Test
    @DisplayName("multi POST 在读取 body 之前返回 403")
    void multiDoesNotReadBody() throws IOException {
        LayoutFeedbackStateController controller = controller(MULTI, store(), null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("multi 模式不得读取请求体");
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[]{'{', '}'});
        request.setParameter("surveyId", SURVEY_ID);

        org.springframework.http.ResponseEntity<?> response =
                controller.saveState(request, SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

}
