package top.sywyar.pixivdownload;

import org.junit.jupiter.api.DisplayName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.web.HttpParameterBinding;
import top.sywyar.pixivdownload.i18n.AppMessages;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("在途请求失库映射为可判定的 503，响应不暴露底层连接详情")
    void unavailableDatabaseHasControlledResponse() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new UnavailableController())
                .setControllerAdvice(handler).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/fixture/database-loss")
                        .locale(Locale.ENGLISH))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").isNotEmpty());
    }

    @org.springframework.web.bind.annotation.RestController
    static class UnavailableController {
        @org.springframework.web.bind.annotation.GetMapping("/fixture/database-loss")
        String unavailable() { throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException(); }
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestI18nBeans.appMessages());

    @Test
    @DisplayName("缺少必填查询参数应经真实绑定返回本地化 JSON 400")
    void missingQueryParameterReturnsBadRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ParameterController())
                .setControllerAdvice(handler).build();
        mvc.perform(get("/api/parameter-fixture").locale(Locale.US).accept("text/html"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(Locale.US, "error.request.field.required", "ids")))
                .andExpect(jsonPath("$.in").value("query"))
                .andExpect(jsonPath("$.field").value("ids"))
                .andExpect(jsonPath("$.reason").value("required"));
        mvc.perform(get("/api/parameter-fixture").param("ids", "42"))
                .andExpect(status().isOk()).andExpect(content().string("42"));
    }

    @Test
    void distinguishesBindingFailuresAndPreservesBinaryNotFound() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ParameterController()).setControllerAdvice(handler).build();
        mvc.perform(get("/api/number/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                .andExpect(jsonPath("$.in").value("path")).andExpect(jsonPath("$.field").value("id"))
                .andExpect(jsonPath("$.reason").value("type"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/number/1"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("http.status.405"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/number/1")
                        .contentType("text/plain").content("abc"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("http.status.415"));
        mvc.perform(get("/api/number/1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("error.request.not-found"));
        mvc.perform(get("/api/missing-empty")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("error.request.not-found"));
        mvc.perform(get("/api/missing-bytes")).andExpect(status().isNotFound()).andExpect(content().bytes(new byte[0]));
    }

    @Test
    void locatesMissingUploadAndNestedBodyFields() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ParameterController()).setControllerAdvice(handler).build();
        mvc.perform(multipart("/api/parameter-upload")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                .andExpect(jsonPath("$.in").value("part")).andExpect(jsonPath("$.field").value("file"))
                .andExpect(jsonPath("$.reason").value("required"));
        mvc.perform(post("/api/parameter-body").contentType("application/json").content("{\"items\":[{}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("error.request.validation"))
                .andExpect(jsonPath("$.in").value("body")).andExpect(jsonPath("$.field").value("/items/0/name"))
                .andExpect(jsonPath("$.reason").value("required"));
        mvc.perform(post("/api/parameter-body").contentType("application/json").content("{\"items\":[{\"name\":{}}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("error.request.body.invalid"))
                .andExpect(jsonPath("$.in").value("body")).andExpect(jsonPath("$.field").value("/items/0/name"))
                .andExpect(jsonPath("$.reason").value("type"));
    }

    @Test
    void keepsDomainCodeAndAdditionalDetailsWhenLocalHandlerMatchesConversionCause() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(LocalBindingConfig.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/local-bind/invalid-id").locale(Locale.US)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DOMAIN_INVALID")).andExpect(jsonPath("$.context").value("lookup"))
                    .andExpect(jsonPath("$.in").value("path")).andExpect(jsonPath("$.field").value("id"))
                    .andExpect(jsonPath("$.reason").value("type"))
                    .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(Locale.US, "error.request.field.type", "id")));
            for (var field : List.of("offset", "limit")) {
                mvc.perform(get("/api/local-pagination").param(field, "-1")).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("DOMAIN_INVALID")).andExpect(jsonPath("$.context").value("lookup"))
                        .andExpect(jsonPath("$.in").value("query")).andExpect(jsonPath("$.field").value(field))
                        .andExpect(jsonPath("$.reason").value("out_of_range"));
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({GlobalExceptionHandler.class, HttpParameterBinding.class, LocalBindingController.class})
    static class LocalBindingConfig {
        @Bean AppMessages messages() { return TestI18nBeans.appMessages(); }
    }

    @RestController
    static class LocalBindingController {
        record DomainError(String code, String error, String context) implements ApiErrorResponse {}

        @GetMapping("/api/local-bind/{id}") long lookup(@PathVariable("id") long id) { return id; }

        @GetMapping("/api/local-pagination")
        void page(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "10") int limit) {
            top.sywyar.pixivdownload.common.InvalidPage.check(offset, limit, 10, null);
        }

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<DomainError> invalid(IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(new DomainError("DOMAIN_INVALID", failure.getMessage(), "lookup"));
        }
    }

    @RestController
    static class ParameterController {
        record Item(@NotBlank String name) {}
        record Payload(List<@Valid Item> items) {}

        @PostMapping("/api/parameter-upload") void upload(@RequestParam("file") MultipartFile file) {}
        @PostMapping("/api/parameter-body") void body(@Valid @RequestBody Payload payload) {}

        @GetMapping("/api/number/{id}")
        ResponseEntity<ApiErrorResponse.Basic> number(@org.springframework.web.bind.annotation.PathVariable("id") long id) {
            return ResponseEntity.notFound().build();
        }

        @org.springframework.web.bind.annotation.PostMapping(value = "/api/number/{id}", consumes = "application/json")
        void post() {}

        @GetMapping("/api/missing-empty")
        ResponseEntity<Void> missingEmpty() { return ResponseEntity.notFound().build(); }

        @GetMapping("/api/missing-bytes")
        ResponseEntity<byte[]> missingBytes() { return ResponseEntity.notFound().build(); }

        @GetMapping("/api/parameter-fixture")
        String read(@RequestParam("ids") String ids) {
            return ids;
        }
    }

    @Test
    @DisplayName("安全异常应返回受控的 400 提示")
    void shouldHandle400ForSecurityException() {
        SecurityException ex = new SecurityException("只允许 HTTPS 协议的下载 URL");

        ResponseEntity<ApiErrorResponse> response = handler.handleSecurity(ex, Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.request.security");
        assertThat(response.getBody().error()).isEqualTo(TestI18nBeans.appMessages().get(Locale.US, "error.request.security"));
    }

    @Test
    @DisplayName("未知参数异常应返回 400 且不回显原始消息")
    void shouldHandle400ForIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException(
                "Conflicting acquisition credential headers");

        ResponseEntity<ApiErrorResponse> response = handler.handleIllegalArgument(ex, Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.request.param.invalid");
        assertThat(response.getBody().error())
                .isEqualTo(TestI18nBeans.appMessages().get(Locale.US, "error.request.param.invalid"));
    }

    @Test
    @DisplayName("通用异常应返回本地化的安全提示")
    void shouldHandle500ForGenericException() {
        Exception ex = new RuntimeException("意外错误");

        ResponseEntity<ApiErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.unexpected");
        assertThat(response.getBody().error()).isEqualTo(TestI18nBeans.appMessages().get(Locale.SIMPLIFIED_CHINESE, "error.unexpected"));
    }

    @Test
    @DisplayName("异常消息为 null 时应返回本地化默认消息")
    void shouldHandleNullMessage() {
        Exception ex = new RuntimeException();

        ResponseEntity<ApiErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.unexpected");
        assertThat(response.getBody().error()).isEqualTo("发生未处理异常");
    }

    @Test
    @DisplayName("客户端断开导致的 IO 异常应视为无响应体的正常断连")
    void shouldTreatClientDisconnectAsNoContent() {
        IOException ex = new IOException("你的主机中的软件中止了一个已建立的连接。");

        ResponseEntity<?> response = handler.handleIOException(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("循环原因链仍返回 IO 错误响应")
    void cyclicIoCausesReturnErrorResponse() {
        class GuardedFailure extends IOException {
            private int causeReads;

            @Override
            public synchronized Throwable getCause() {
                // 错误实现应直接失败，避免测试线程无限遍历。
                assertThat(++causeReads).isLessThanOrEqualTo(3);
                return super.getCause();
            }
        }
        IOException first = new GuardedFailure();
        IOException second = new GuardedFailure();
        first.initCause(second);
        second.initCause(first);

        ResponseEntity<?> response = handler.handleIOException(first, Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isInstanceOfSatisfying(ApiErrorResponse.class,
                error -> assertThat(error.code()).isEqualTo("error.io"));
    }

    @Test
    @DisplayName("嵌套断连原因仍返回无响应体且普通 IO 错误保留错误码")
    void nestedIoCausesKeepResponseSemantics() {
        IOException disconnected = new IOException("outer", new IOException("Broken pipe"));
        IOException failed = new IOException("read failed", new IOException("disk failed"));

        ResponseEntity<?> disconnectResponse = handler.handleIOException(disconnected, Locale.US);
        ResponseEntity<?> errorResponse = handler.handleIOException(failed, Locale.US);

        assertThat(disconnectResponse.getStatusCode().value()).isEqualTo(204);
        assertThat(disconnectResponse.getBody()).isNull();
        assertThat(errorResponse.getStatusCode().value()).isEqualTo(500);
        assertThat(errorResponse.getBody()).isInstanceOfSatisfying(ApiErrorResponse.class,
                error -> assertThat(error.code()).isEqualTo("error.io"));
    }

    @Test
    @DisplayName("队列清退竞态应返回本地化 503 而不是裸 500")
    void shouldHandleQuiescedQueueAsServiceUnavailable() {
        ResponseEntity<ApiErrorResponse> response = handler.handleQueueNotAccepting(
                new QueueNotAcceptingException("illust"), Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("plugin.unavailable.quiesced");
        assertThat(response.getBody().error()).isEqualTo("插件正在停用中，暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("下载队列已满时应返回本地化 429")
    void shouldHandleFullDownloadQueueAsTooManyRequests() {
        ResponseEntity<ApiErrorResponse> response = handler.handleQueueFull(
                new TaskRejectedException("full"), Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("task.queue.full");
        assertThat(response.getBody().error()).isEqualTo("任务排队已满，请稍后重试");
    }

    @Test
    @DisplayName("插画与小说可见性领域失败应映射为各自本地化 403")
    void shouldMapWorkVisibilityDeniedToLocalizedForbidden() {
        ResponseEntity<ApiErrorResponse> artwork = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.ARTWORK, 42L),
                Locale.SIMPLIFIED_CHINESE);
        ResponseEntity<ApiErrorResponse> novel = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.NOVEL, 43L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(artwork.getStatusCode().value()).isEqualTo(403);
        assertThat(artwork.getBody()).isNotNull();
        assertThat(artwork.getBody().code()).isEqualTo("guest.invite.forbidden");
        assertThat(artwork.getBody().error()).isEqualTo("该作品不在你的可见范围内");
        assertThat(novel.getStatusCode().value()).isEqualTo(403);
        assertThat(novel.getBody()).isNotNull();
        assertThat(novel.getBody().code()).isEqualTo("guest.invite.novel.forbidden");
        assertThat(novel.getBody().error()).isEqualTo("该小说不在你的可见范围内");
    }

    @Test
    @DisplayName("作品本地文件删除失败应在 Web 层映射为本地化 409")
    void shouldMapWorkDeletionFailureToLocalizedConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleWorkDeletion(
                new WorkDeletionException(
                        WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED,
                        WorkType.NOVEL,
                        42L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("work.delete.file-failed");
        assertThat(response.getBody().error())
                .isEqualTo("小说 42 的磁盘文件未能全部删除（被锁定或权限不足），"
                        + "已中止数据库清理，请稍后重试或检查文件占用情况");
    }

    @Test
    @DisplayName("不安全删除路径应返回本地化 409 且不暴露服务器路径")
    void shouldMapUnsafeDeletionPathToLocalizedConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnsafeDeletionPath(
                new UnsafeDeletionPathException("C:\\downloads\\linked"),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("work.delete.path-unsafe");
        assertThat(response.getBody().error())
                .isEqualTo(TestI18nBeans.appMessages().get(Locale.SIMPLIFIED_CHINESE, "work.delete.path-unsafe"))
                .doesNotContain("C:\\downloads\\linked");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "generic,500,error.unexpected", "linkage,500,error.unexpected",
            "io,500,error.io", "security,400,error.request.security",
            "argument,400,error.request.param.invalid", "status,404,http.status.404"
    })
    @DisplayName("真实 MVC 异常响应保留状态与错误码且不暴露诊断或嵌套原因")
    void mvcFailuresDoNotExposeDiagnostics(String kind, int statusCode, String code) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureController()).setControllerAdvice(handler).build();
        for (Locale locale : new Locale[]{Locale.US, Locale.SIMPLIFIED_CHINESE}) {
            String body = mvc.perform(get("/api/error-fixture").param("kind", kind).locale(locale))
                    .andExpect(status().is(statusCode))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(locale,
                            kind.equals("status") ? "error.request.not-found" : code)))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("internal-probe", "NoSuchMethodError", "Handler dispatch failed", "stackTrace");
        }
    }

    @RestController
    static class FailureController {
        @GetMapping("/api/error-fixture")
        String fail(@RequestParam("kind") String kind) throws IOException {
            String detail = "internal-probe C:/private/database.db token=private-value";
            switch (kind) {
                case "linkage" -> throw new NoSuchMethodError(detail);
                case "io" -> throw new IOException(detail);
                case "security" -> throw new SecurityException(detail);
                case "argument" -> throw new IllegalArgumentException(detail);
                case "status" -> throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, detail);
                default -> throw new RuntimeException(detail, new IllegalStateException(detail));
            }
        }
    }

    @Test
    @DisplayName("Pixiv 稳定端口的上游状态应保持本地化 502 映射")
    void shouldMapPixivAjaxHttpFailureToLocalizedBadGateway() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePixivAjax(
                new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.pixiv.upstream.unauthorized");
        assertThat(response.getBody().error())
                .contains("Pixiv 拒绝了请求")
                .doesNotContain("403");
    }

    @Test
    @DisplayName("Pixiv 响应超过安全上限时应返回明确的本地化 502")
    void shouldMapOversizedPixivResponseToLocalizedBadGateway() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePixivAjax(
                new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0),
                Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.pixiv.response.too-large");
        assertThat(response.getBody().error())
                .isEqualTo("The Pixiv response exceeded the safe size limit and was rejected.");
    }
}
