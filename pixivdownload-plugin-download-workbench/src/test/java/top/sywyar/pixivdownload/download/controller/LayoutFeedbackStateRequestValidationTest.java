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

@DisplayName("布局偏好调查请求校验")
class LayoutFeedbackStateRequestValidationTest extends LayoutFeedbackStateControllerTestSupport {
    /* ============================================================
       G. 输入校验
    ============================================================ */

    @Test
    @DisplayName("unknown command → 400")
    void unknownCommandRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("explode")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown JSON field → 400")
    void unknownJsonFieldRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"surveyId\":\"" + SURVEY_ID
                                + "\",\"command\":\"never\",\"suggestion\":\"evil\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("旧协议 expectedRevision 作为未知字段 → 400")
    void expectedRevisionIsUnknownFieldRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"surveyId\":\"" + SURVEY_ID
                                + "\",\"command\":\"never\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
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
    @DisplayName("缺 surveyId → 400（GET / POST）")
    void missingSurveyIdRejected() throws Exception {
        MockMvc mockMvc = mockMvc(SOLO);
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isBadRequest());
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("never")))
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
                        .content(commandBody("never")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("record_seen 无 layoutIds → 400")
    void recordSeenWithoutLayoutIdsRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("record_seen")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("state 命令携带 layoutIds → 400")
    void stateCommandWithLayoutIdsRejected() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody("submitted", SURVEY_ID,
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
        String command = commandJson("submitted", SURVEY_ID, null);
        int padding = LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES - command.length();
        assertThat(padding).as("测试前置：命令 JSON 不得超过上限").isGreaterThanOrEqualTo(0);
        StringBuilder body = new StringBuilder(command);
        body.append(" ".repeat(padding));
        assertThat(body.length()).isEqualTo(LayoutFeedbackStateController.MAX_COMMAND_BODY_BYTES);

        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));
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
                        .content(commandBody("submitted")))
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
                            .content(commandBody("submitted")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("submitted"))
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
                    return new CountingStream(new ByteArrayInputStream(commandBody("submitted")), reads);
                }
            };
            request.addHeader(HttpHeaders.CONTENT_TYPE, contentType);
            request.setParameter("surveyId", SURVEY_ID);
            request.setContent(commandBody("submitted"));

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

    @Test
    @DisplayName("接受引号内分号 / 引号内等号 / 转义引号与多参数")
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
                            .content(commandBody("submitted")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("submitted"))
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
                    return new CountingStream(new ByteArrayInputStream(commandBody("submitted")), reads);
                }
            };
            request.addHeader(HttpHeaders.CONTENT_TYPE, contentType);
            request.setParameter("surveyId", SURVEY_ID);
            request.setContent(commandBody("submitted"));

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
                        .content(commandBody("submitted")))
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
                        commandBody("submitted")), reads);
            }
        };
        request.setContentType(MediaType.TEXT_PLAIN_VALUE);
        request.setParameter("surveyId", SURVEY_ID);
        request.setContent(commandBody("submitted"));

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
                        .content(commandBody("submitted")))
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
        request.setContent(commandBody("submitted"));

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
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsNoStorePrivate()));
    }

    @Test
    @DisplayName("application/*+json → 接受（项目规范）")
    void plusJsonContentTypeAccepted() throws Exception {
        mockMvc(SOLO).perform(post(ENDPOINT).param("surveyId", SURVEY_ID)
                        .contentType("application/layout-feedback+json")
                        .content(commandBody("submitted")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
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
                        .content(commandBody("submitted", OTHER_SURVEY_ID, null)))
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

}
