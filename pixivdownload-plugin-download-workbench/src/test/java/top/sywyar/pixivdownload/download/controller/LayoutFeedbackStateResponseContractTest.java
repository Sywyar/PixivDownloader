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

@DisplayName("布局偏好调查响应协议")
class LayoutFeedbackStateResponseContractTest extends LayoutFeedbackStateControllerTestSupport {
    /* ============================================================
       HTTP JSON 线格式（小写 wire value）
    ============================================================ */

    @Test
    @DisplayName("POST submitted 后 GET 持续返回小写 submitted，响应不出现大写枚举名")
    void realHttpJsonStaysLowercase() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        MvcResult postResult = mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andReturn();

        MvcResult getResult = mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andReturn();

        String postBody = postResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String getBody = getResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(postBody + getBody)
                .as("响应不得出现大写枚举名")
                .doesNotContain("SUBMITTED")
                .doesNotContain("NEVER")
                .doesNotContain("SNOOZED");
    }

    @Test
    @DisplayName("POST snooze / never 的 JSON 为 snoozed / never")
    void realHttpJsonSnoozeAndNever() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("snooze")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snoozed"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").isNumber());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("never"));
    }

    @Test
    @DisplayName("旧大写状态文件 GET 仍返回小写 wire value")
    void legacyUpperCaseFileServedAsLowercase() throws Exception {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "{\"schemaVersion\":1,\"revision\":1,"
                        + "\"state\":{\"surveyId\":\"" + SURVEY_ID
                        + "\",\"status\":\"SUBMITTED\",\"updatedAt\":1,\"snoozedUntil\":0},"
                        + "\"seen\":{}}",
                StandardCharsets.UTF_8);

        MvcResult result = mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("SUBMITTED");
    }

    /* ============================================================
       Cache-Control no-store
    ============================================================ */

    @Test
    @DisplayName("GET 200 / POST 200 响应 Cache-Control 为 no-store, private")
    void successResponsesAreNoStore() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("400 / 403 / 413 / 415 / 503 响应同样 no-store")
    void errorResponsesAreNoStore() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("explode")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        mockMvc.perform(get(ENDPOINT).param("surveyId", "bad id"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        MockMvc multiMvc = mockMvc(MULTI);
        multiMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        multiMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        Path file = tempDir.resolve("degraded/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.createDirectory(file);
        LayoutFeedbackStateStore degradedStore = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(mockRuntimePath(file.getParent())));
        mockMvc(SOLO, degradedStore, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /* ============================================================
       Clock 负值防御（服务端墙钟回拨 / 时钟源异常）
    ============================================================ */

    @Test
    @DisplayName("Clock 返回负值：GET / POST 200 的视图合法，Store 不接收负时间")
    void negativeClockClampedToZero() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(-5000), ZoneOffset.UTC);
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canShow").value(true))
                .andExpect(jsonPath("$.retryAfterMs").value(0));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("snooze")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("snoozed"))
                .andExpect(jsonPath("$.canShow").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value((7L * 24 * 60 * 60 * 1000)));

        org.mockito.ArgumentCaptor<Long> nowCaptor = forClass(Long.class);
        verify(store, atLeast(1)).apply(any(), nowCaptor.capture());
        assertThat(nowCaptor.getAllValues())
                .as("Store 不得接收负时间")
                .allMatch(value -> value >= 0);
        String persisted = Files.readString(
                tempDir.resolve("state/download-workbench/layout-feedback-state.json"),
                StandardCharsets.UTF_8);
        assertThat(persisted).as("状态文件不含 serverTime").doesNotContain("serverTime");
    }

}
