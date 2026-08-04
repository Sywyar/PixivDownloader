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
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
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
import java.util.Map;

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

@DisplayName("布局偏好调查服务端状态端点")
class LayoutFeedbackStateControllerTest {

    private static final String SOLO = "solo";
    private static final String MULTI = "multi";
    private static final String INSTALL_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
    private static final String ENDPOINT = "/api/layout-feedback/state";

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

    private LayoutFeedbackStateController controller(String mode, LayoutFeedbackStateStore store,
                                                     InstallIdentityProvider identityProvider,
                                                     Clock clock) {
        ApplicationModeProvider applicationModeProvider = mock(ApplicationModeProvider.class);
        when(applicationModeProvider.getMode()).thenReturn(mode);
        return new LayoutFeedbackStateController(
                store, applicationModeProvider,
                identityProvider == null ? () -> INSTALL_ID : identityProvider,
                clock);
    }

    private MockMvc mockMvc(String mode, LayoutFeedbackStateStore store,
                            InstallIdentityProvider identityProvider) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller(mode, store, identityProvider))
                .build();
    }

    private MockMvc mockMvc(String mode) {
        return mockMvc(mode, store(), null);
    }

    private String commandJson(String command, long expectedRevision, String surveyId,
                               List<String> layoutIds) {
        StringBuilder json = new StringBuilder();
        json.append("{\"expectedRevision\":").append(expectedRevision)
                .append(",\"surveyId\":\"").append(surveyId)
                .append("\",\"command\":\"").append(command).append("\"");
        if (layoutIds != null) {
            json.append(",\"layoutIds\":[");
            for (int i = 0; i < layoutIds.size(); i++) {
                if (i > 0) {
                    json.append(",");
                }
                json.append("\"").append(layoutIds.get(i)).append("\"");
            }
            json.append("]");
        }
        return json.append("}").toString();
    }

    private byte[] commandBody(String command, long expectedRevision) {
        return commandBody(command, expectedRevision, SURVEY_ID, null);
    }

    private byte[] commandBody(String command, long expectedRevision, String surveyId,
                               List<String> layoutIds) {
        return commandJson(command, expectedRevision, surveyId, layoutIds)
                .getBytes(StandardCharsets.UTF_8);
    }

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

    private static String distinctIdOf(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("distinctId").asText();
    }

    /* ============================================================
       H. 模式
    ============================================================ */

    @Test
    @DisplayName("solo GET 成功")
    void soloGetSucceeds() throws Exception {
        mockMvc(SOLO).perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stateAvailable").value(true))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.seen").isMap());
    }

    @Test
    @DisplayName("solo POST submitted 成功并返回小写 submitted")
    void soloPostSucceeds() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.state.status").value("submitted"));
    }

    @Test
    @DisplayName("multi GET / POST 一律 403，不调用 InstallIdentityProvider、不写状态文件、不读取 body、不触发 Store 加载")
    void multiRejectsEverything() throws Exception {
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        MockMvc mockMvc = mockMvc(MULTI, store(), identityProvider);

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isForbidden());

        verify(identityProvider, never()).get();
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

    /* ============================================================
       G. 输入校验
    ============================================================ */

    @Test
    @DisplayName("unknown command → 400")
    void unknownCommandRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("explode", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown JSON field → 400")
    void unknownJsonFieldRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"surveyId\":\"" + SURVEY_ID
                                + "\",\"command\":\"never\",\"suggestion\":\"evil\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非 JSON → 400")
    void nonJsonRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("negative revision → 400")
    void negativeRevisionRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("缺 surveyId → 400（GET / POST）")
    void missingSurveyIdRejected() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isBadRequest());
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非法 surveyId → 400（GET / POST）")
    void invalidSurveyIdRejected() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(get(ENDPOINT).param("surveyId", "bad survey id"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(ENDPOINT).param("surveyId", "bad survey id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("record_seen 无 layoutIds → 400")
    void recordSeenWithoutLayoutIdsRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("state 命令携带 layoutIds → 400")
    void stateCommandWithLayoutIdsRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0, SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isBadRequest());
    }

    /* ============================================================
       请求体前置限制（Content-Length / chunked / 空 body / 上限）
    ============================================================ */

    @Test
    @DisplayName("请求体超限 → 413，不解析、不修改文件")
    void oversizedBodyRejected() throws Exception {
        byte[] oversized = new byte[LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("413 不得创建状态文件")
                .isFalse();
    }

    @Test
    @DisplayName("Content-Length 明确超限：输入流读取次数为 0，Store 未调用")
    void declaredOversizedBodyNotReadAtAll() throws IOException {
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        final int[] reads = {0};
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
            @Override
            public ServletInputStream getInputStream() {
                reads[0]++;
                return new CountingStream(new ByteArrayInputStream(new byte[0]), reads);
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES + 1]);

        org.springframework.http.ResponseEntity<?> response =
                controller.saveState(request, SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(reads[0]).as("声明超限后不得读取输入流").isZero();
        verify(store, never()).apply(any(), anyLong());
    }

    @Test
    @DisplayName("Content-Length 未知（chunked）超过限制：只读到 MAX+1，413，不解析 JSON")
    void chunkedOversizedBodyStopsAtMaxPlusOne() throws IOException {
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        byte[] payload = new byte[LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES + 5];
        final int[] consumed = {0};
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new CountingStream(new ByteArrayInputStream(payload), consumed);
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);

        org.springframework.http.ResponseEntity<?> response =
                controller.saveState(request, SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(consumed[0]).as("chunked 超限只读到 MAX+1").isEqualTo(
                LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES + 1);
        verify(store, never()).apply(any(), anyLong());
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("413 不得创建状态文件")
                .isFalse();
    }

    @Test
    @DisplayName("正好 MAX 的请求体仍可解析")
    void exactlyMaxBodyParses() throws Exception {
        String command = commandJson("submitted", 0, SURVEY_ID, null);
        int padding = LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES - command.length();
        assertThat(padding).as("测试前置：命令 JSON 不得超过上限").isGreaterThanOrEqualTo(0);
        StringBuilder body = new StringBuilder(command);
        body.append(" ".repeat(padding));
        assertThat(body.length()).isEqualTo(LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES);

        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("submitted"));
    }

    @Test
    @DisplayName("空 body → 400")
    void emptyBodyRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Content-Type 非 application/json → 415")
    void wrongContentTypeRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    /* ============================================================
       Content-Type 严格解析（Spring MediaType）
    ============================================================ */

    @Test
    @DisplayName("接受合法 application/json 与具体 +json 子类型（大小写与参数变体）")
    void acceptsStrictJsonContentTypes() throws Exception {
        List<String> accepted = List.of(
                "application/json",
                "application/json;charset=UTF-8",
                "application/json; charset=UTF-8",
                "APPLICATION/JSON",
                "application/problem+json",
                "application/vnd.pixivdownload+json",
                "application/vnd.example+json;charset=UTF-8");
        for (int i = 0; i < accepted.size(); i++) {
            // 每个 Content-Type 用独立状态目录的 store（同一 store 重复 submitted 是
            // 幂等 no-op，不会推进 revision，因此不能复用同一 store）。
            Path dir = tempDir.resolve("accept-" + i);
            LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                    new LayoutFeedbackStateFiles(mockRuntimePath(dir)));
            mockMvc(SOLO, store, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                            .contentType(accepted.get(i))
                            .content(commandBody("submitted", 0)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state.status").value("submitted"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        }
    }

    @Test
    @DisplayName("拒绝 wildcard 与语法损坏 Content-Type → 415 且 no-store，不读 body、不调用 Store / InstallIdentityProvider、不创建文件、revision 不变")
    void rejectsMalformedContentTypesWithoutSideEffects() throws Exception {
        LayoutFeedbackStateStore store = spy(store());
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        when(identityProvider.get()).thenReturn(INSTALL_ID);
        LayoutFeedbackStateController controller = controller(SOLO, store, identityProvider);
        List<String> rejected = List.of(
                "",
                "text/plain",
                "text/json",
                "application/xml",
                "application/*",
                "application/*+json",
                "*/*",
                "application/json; charset=\"unterminated",
                "application/json; invalid parameter",
                "application/json; ===",
                "application/json garbage");
        for (String contentType : rejected) {
            final int[] reads = {0};
            MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
                @Override
                public ServletInputStream getInputStream() {
                    reads[0]++;
                    return new CountingStream(new ByteArrayInputStream(commandBody("submitted", 0)), reads);
                }
            };
            request.addHeader(HttpHeaders.CONTENT_TYPE, contentType);
            request.setParameter("surveyId", SURVEY_ID);
            request.setContent(commandBody("submitted", 0));

            org.springframework.http.ResponseEntity<?> response =
                    controller.saveState(request, SURVEY_ID);

            assertThat(response.getStatusCode().value())
                    .as("Content-Type=" + contentType + " 应返回 415")
                    .isEqualTo(415);
            assertThat(response.getHeaders().getCacheControl())
                    .as("Content-Type=" + contentType + " 响应必须 no-store, private")
                    .contains("no-store").contains("private");
            assertThat(reads[0]).as("Content-Type=" + contentType + " 不得读取请求体").isZero();
        }
        verify(store, never()).apply(any(), anyLong());
        verify(store, never()).snapshot();
        verify(store, never()).degraded();
        verify(identityProvider, never()).get();
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("415 路径不得创建状态文件")
                .isFalse();

        MockMvc verifyMvc = mockMvc(SOLO, store, identityProvider);
        verifyMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0));
    }

    /* ============================================================
       缺 surveyId / Content-Type 错误路径（进入 Controller 后由
       statusResponse 统一返回，全部 no-store, private）
    ============================================================ */

    @Test
    @DisplayName("GET 缺 surveyId → 400 且 no-store, private")
    void missingGetSurveyIdIsNoStore() throws Exception {
        mockMvc(SOLO).perform(get(ENDPOINT))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("POST 缺 surveyId → 400 且 no-store, private，不读取 Store")
    void missingPostSurveyIdIsNoStoreAndSkipsStore() throws Exception {
        LayoutFeedbackStateStore store = spy(store());
        mockMvc(SOLO, store, null).perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        verify(store, never()).snapshot();
        verify(store, never()).degraded();
        verify(store, never()).apply(any(), anyLong());
    }

    @Test
    @DisplayName("text/plain → 415 且 no-store, private，不读取 body、不调用 Store")
    void textPlainIsNoStoreAndSkipsBodyRead() throws IOException {
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null);
        final int[] reads = {0};
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
            @Override
            public ServletInputStream getInputStream() {
                reads[0]++;
                return new CountingStream(new ByteArrayInputStream(
                        commandBody("submitted", 0)), reads);
            }
        };
        request.setContentType(MediaType.TEXT_PLAIN_VALUE);
        request.setParameter("surveyId", SURVEY_ID);
        request.setContent(commandBody("submitted", 0));

        org.springframework.http.ResponseEntity<?> response =
                controller.saveState(request, SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store").contains("private");
        assertThat(reads[0]).as("415 不得读取请求体").isZero();
        verify(store, never()).apply(any(), anyLong());
    }

    @Test
    @DisplayName("无 Content-Type → 415 且 no-store, private")
    void missingContentTypeIsNoStore() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("非法 Content-Type 字符串 → 415 且 no-store, private")
    void invalidContentTypeIsNoStore() throws IOException {
        LayoutFeedbackStateController controller = controller(SOLO, store(), null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT);
        request.setContentType("application/json garbage");
        request.setParameter("surveyId", SURVEY_ID);
        request.setContent(commandBody("submitted", 0));

        org.springframework.http.ResponseEntity<?> response =
                controller.saveState(request, SURVEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store").contains("private");
    }

    @Test
    @DisplayName("application/json;charset=UTF-8 → 正常接受")
    void jsonWithCharsetAccepted() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType("application/json;charset=UTF-8")
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("submitted"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("application/*+json → 接受（项目规范）")
    void plusJsonContentTypeAccepted() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType("application/layout-feedback+json")
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("submitted"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    /* ============================================================
       query / body surveyId 一致性
    ============================================================ */

    @Test
    @DisplayName("query=A body=B → 400，revision 不变，Store 不调用，不返回 scoped ID")
    void mismatchedQueryAndBodySurveyIdRejected() throws Exception {
        LayoutFeedbackStateStore store = spy(store());
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        when(identityProvider.get()).thenReturn(INSTALL_ID);
        MockMvc mockMvc = mockMvc(SOLO, store, identityProvider);

        MvcResult result = mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0, OTHER_SURVEY_ID, null)))
                .andExpect(status().isBadRequest())
                .andReturn();

        verify(store, never()).apply(any(), anyLong());
        verify(identityProvider, never()).get();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("400 响应不返回 scoped ID")
                .doesNotContain("plf_");
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("400 不得创建状态文件")
                .isFalse();
        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0));
    }

    /* ============================================================
       CAS / 409
    ============================================================ */

    @Test
    @DisplayName("stale revision → 409 并携带当前完整快照（小写 submitted）")
    void staleRevisionReturns409WithSnapshot() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk());

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.state.status").value("submitted"));
    }

    @Test
    @DisplayName("409 不修改状态文件")
    void conflictDoesNotWriteFile() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandBody("submitted", 0))).andExpect(status().isOk());
        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        String before = Files.readString(file, StandardCharsets.UTF_8);

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict());

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    @DisplayName("旧 Survey state 的 GET 返回 state=null，seen 继续返回")
    void otherSurveyStateIsNullButSeenKept() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen", 0, SURVEY_ID,
                                List.of("pixiv-batch-landscape"))))
                .andExpect(status().isOk());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 1)))
                .andExpect(status().isOk());

        mockMvc.perform(get(ENDPOINT).param("surveyId", OTHER_SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.seen['pixiv-batch-landscape'].lastSeenAt").exists())
                .andExpect(jsonPath("$.revision").value(2));
    }

    /* ============================================================
       degraded
    ============================================================ */

    @Test
    @DisplayName("store degraded：GET 仍返回 scoped 身份且 stateAvailable=false，POST 503")
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
                .andExpect(jsonPath("$.distinctId").value(
                        org.hamcrest.Matchers.matchesPattern("^plf_[0-9a-f]{64}$")));

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isServiceUnavailable());
    }

    /* ============================================================
       HTTP JSON 线格式（小写 wire value）
    ============================================================ */

    @Test
    @DisplayName("POST submitted 后 GET 持续返回小写 submitted，响应不出现大写枚举名")
    void realHttpJsonStaysLowercase() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        MvcResult postResult = mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("submitted"))
                .andReturn();

        MvcResult getResult = mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("submitted"))
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
                        .content(commandBody("snooze", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("snoozed"))
                .andExpect(jsonPath("$.state.snoozedUntil").isNumber());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("never"));
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
                .andExpect(jsonPath("$.state.status").value("submitted"))
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
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("409 / 400 / 403 / 413 / 503 响应同样 no-store")
    void errorResponsesAreNoStore() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("explode", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        mockMvc.perform(get(ENDPOINT).param("surveyId", "bad id"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        MockMvc multiMvc = mockMvc(MULTI);
        multiMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        multiMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        Path file = tempDir.resolve("degraded/layout-feedback-state.json");
        Files.createDirectories(file.getParent());
        Files.createDirectory(file);
        LayoutFeedbackStateStore degradedStore = new LayoutFeedbackStateStore(
                new LayoutFeedbackStateFiles(mockRuntimePath(file.getParent())));
        mockMvc(SOLO, degradedStore, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));

        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /* ============================================================
       serverTime 协议
    ============================================================ */

    private static final long FIXED_SERVER_TIME = 1_786_000_000_000L;

    @Test
    @DisplayName("GET 200 / POST 200 / POST 409 均携带数值 serverTime")
    void successResponsesCarryServerTime() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").isNumber());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").isNumber());
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.serverTime").isNumber());
    }

    @Test
    @DisplayName("固定 Clock：GET / POST 200 / POST 409 的 serverTime 精确等于固定值")
    void fixedClockServerTimeExactOnAllResponses() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(FIXED_SERVER_TIME), ZoneOffset.UTC);
        LayoutFeedbackStateController controller = controller(SOLO, store(), null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(FIXED_SERVER_TIME));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(FIXED_SERVER_TIME));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.serverTime").value(FIXED_SERVER_TIME));
    }

    @Test
    @DisplayName("同一 POST 中 Store apply 使用的 now 与响应 serverTime 相同")
    void postApplyNowEqualsResponseServerTime() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(FIXED_SERVER_TIME), ZoneOffset.UTC);
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(FIXED_SERVER_TIME));

        org.mockito.ArgumentCaptor<Long> nowCaptor = forClass(Long.class);
        verify(store).apply(any(), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isEqualTo(FIXED_SERVER_TIME);
    }

    @Test
    @DisplayName("serverTime 不写入状态文件，状态文件 schema 不含 serverTime")
    void serverTimeNotPersistedToStateFile() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").isNumber());

        Path file = tempDir.resolve("state/download-workbench/layout-feedback-state.json");
        String persisted = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(persisted).as("状态文件不得包含 serverTime").doesNotContain("serverTime");
        assertThat(persisted).contains("\"schemaVersion\":1");
    }

    @Test
    @DisplayName("Clock 返回负值：GET / POST 200 / POST 409 的 serverTime 均为 0，Store 不接收负时间")
    void negativeClockClampedToZero() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(-5000), ZoneOffset.UTC);
        LayoutFeedbackStateStore store = spy(store());
        LayoutFeedbackStateController controller = controller(SOLO, store, null, clock);
        MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        mockMvc.perform(get(ENDPOINT).param("surveyId", SURVEY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(0));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(0));
        mockMvc.perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.serverTime").value(0));

        org.mockito.ArgumentCaptor<Long> nowCaptor = forClass(Long.class);
        verify(store, atLeast(2)).apply(any(), nowCaptor.capture());
        assertThat(nowCaptor.getAllValues())
                .as("Store 不得接收负时间")
                .allMatch(value -> value >= 0);
        String persisted = Files.readString(
                tempDir.resolve("state/download-workbench/layout-feedback-state.json"),
                StandardCharsets.UTF_8);
        assertThat(persisted).as("状态文件不得包含 serverTime").doesNotContain("serverTime");
    }

    /* ============================================================
       Content-Type 引号参数（quoted semicolon scanner）
    ============================================================ */

    @Test
    @DisplayName("接受引号内分号 / 引号内等号 / 转义引号与多参数：profile=\"a;b\" / profile=\"a=b;c=d\" / note=\"a\\\\\";b\" 等")
    void acceptsQuotedSemicolonContentTypes() throws Exception {
        List<String> accepted = List.of(
                "application/json; profile=\"a;b\"",
                "application/json; profile=\"a;b\"; charset=UTF-8",
                "application/json; profile=\"a=b;c=d\"; charset=UTF-8",
                "application/problem+json; profile=\"https://example.invalid/a;b?x=y\"",
                "application/json; note=\"a\\\";b\"");
        for (int i = 0; i < accepted.size(); i++) {
            Path dir = tempDir.resolve("quoted-accept-" + i);
            LayoutFeedbackStateStore store = new LayoutFeedbackStateStore(
                    new LayoutFeedbackStateFiles(mockRuntimePath(dir)));
            mockMvc(SOLO, store, null).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                            .header(HttpHeaders.CONTENT_TYPE, accepted.get(i))
                            .content(commandBody("submitted", 0)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state.status").value("submitted"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
        }
    }

    @Test
    @DisplayName("拒绝引号外缺失等号的参数段 → 415 且 no-store，不读 body、不调用 Store / InstallIdentityProvider、不创建文件")
    void rejectsBrokenParameterSegmentsWithoutSideEffects() throws IOException {
        LayoutFeedbackStateStore store = spy(store());
        InstallIdentityProvider identityProvider = mock(InstallIdentityProvider.class);
        when(identityProvider.get()).thenReturn(INSTALL_ID);
        LayoutFeedbackStateController controller = controller(SOLO, store, identityProvider);
        List<String> rejected = List.of(
                "application/json; invalid parameter",
                "application/json; profile=\"a;b\"; broken",
                "application/json; profile=\"a;b\"; =x",
                "application/json; =value",
                "application/json; profile=\"unterminated");
        for (String contentType : rejected) {
            final int[] reads = {0};
            MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT) {
                @Override
                public ServletInputStream getInputStream() {
                    reads[0]++;
                    return new CountingStream(new ByteArrayInputStream(commandBody("submitted", 0)), reads);
                }
            };
            request.addHeader(HttpHeaders.CONTENT_TYPE, contentType);
            request.setParameter("surveyId", SURVEY_ID);
            request.setContent(commandBody("submitted", 0));

            org.springframework.http.ResponseEntity<?> response =
                    controller.saveState(request, SURVEY_ID);

            assertThat(response.getStatusCode().value())
                    .as("Content-Type=" + contentType + " 应返回 415")
                    .isEqualTo(415);
            assertThat(response.getHeaders().getCacheControl())
                    .as("Content-Type=" + contentType + " 响应必须 no-store, private")
                    .contains("no-store").contains("private");
            assertThat(reads[0]).as("Content-Type=" + contentType + " 不得读取请求体").isZero();
        }
        verify(store, never()).apply(any(), anyLong());
        verify(store, never()).snapshot();
        verify(store, never()).degraded();
        verify(identityProvider, never()).get();
        assertThat(Files.exists(tempDir.resolve("state/download-workbench/layout-feedback-state.json")))
                .as("415 路径不得创建状态文件")
                .isFalse();
    }

    private static org.hamcrest.Matcher<String> containsNoStorePrivate() {
        return new org.hamcrest.BaseMatcher<String>() {
            @Override
            public boolean matches(Object item) {
                return item instanceof String value
                        && value.contains("no-store") && value.contains("private");
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("Cache-Control containing no-store and private");
            }
        };
    }

    private RuntimePathProvider mockRuntimePath(Path stateDir) {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.resolvePluginStateDirectory(DownloadWorkbenchPlugin.ID))
                .thenReturn(stateDir);
        return runtimePathProvider;
    }

    private java.util.List<Path> corruptFiles(Path file) throws IOException {
        try (var stream = Files.list(file.getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }

    /** 记录 read() 调用次数 / 已消费字节的 ServletInputStream。 */
    private static final class CountingStream extends ServletInputStream {
        private final InputStream delegate;
        private final int[] counter;

        CountingStream(InputStream delegate, int[] counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            counter[0]++;
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                counter[0] += read;
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
        }
    }
}
