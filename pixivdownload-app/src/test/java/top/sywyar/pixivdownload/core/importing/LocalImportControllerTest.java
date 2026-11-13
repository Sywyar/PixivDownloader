package top.sywyar.pixivdownload.core.importing;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("本地导入 HTTP 输入与异步任务协议")
class LocalImportControllerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private LocalImportService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(LocalImportService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LocalImportController(service, TestI18nBeans.appMessages())).build();
    }
    @Test @DisplayName("HTTP 返回接纳状态和精确任务身份，查询与确认共用服务")
    void acceptedAndPolled() throws Exception {
        UUID id = UUID.randomUUID();
        var snapshot = new Snapshot(id, Phase.SCANNING, 0, 0, 0, 0, 0, 0, false, List.of());
        when(service.scan(any())).thenReturn(snapshot); when(service.status(id)).thenReturn(snapshot); when(service.start(id)).thenReturn(snapshot);
        mvc.perform(post("/api/local-import/previews").contentType("application/json").content("{\"artworkIds\":[42],\"templates\":[]}"))
                .andDo(result -> Assertions.assertNull(result.getResolvedException()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.phase").value("SCANNING"));
        verify(service).scan(argThat(r -> r.artworkIds().equals(List.of(42L))));
        mvc.perform(get("/api/local-import/previews/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/local-import/tasks").contentType("application/json").content("{\"previewId\":\"" + id + "\"}"))
                .andExpect(status().isAccepted());
        verify(service).start(id);
    }
    @Test @DisplayName("重复键、尾随 JSON、非法 ID 和超大正文在扫描前拒绝")
    void rejectsUntrustedBodies() throws Exception {
        for (String json : List.of("null", "{} {}", "{\"artworkIds\":[],\"artworkIds\":[1]}", "{\"artworkIds\":[0]}", "{\"artworkIds\":[null]}",
                "{\"artworkIds\":[1.5]}", "{\"artworkIds\":[\"42\"]}", "{\"refreshIndex\":\"true\"}")) {
            mvc.perform(post("/api/local-import/previews").contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IMPORT_REQUEST"))
                    .andExpect(jsonPath("$.error").isString());
        }
        mvc.perform(post("/api/local-import/previews").contentType("application/json").content(" ".repeat(256 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("IMPORT_BODY_LIMIT"));
        verifyNoInteractions(service);
    }
    @Test @DisplayName("HTTP 区分未知和当前模式不可用的变量，按请求语言显示变量名")
    void variableErrorsHaveDetails() throws Exception {
        when(service.scan(any())).thenAnswer(call -> {
            Request request = call.getArgument(0);
            new LocalImportTemplate(request.templates().getFirst());
            throw new AssertionError("Invalid template was accepted");
        });
        for (String variable : List.of("foo", "ai")) {
            String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
                    "templates", List.of(directory + "/{artwork_id}_{" + variable + "}.png")));
            String code = variable.equals("ai") ? "IMPORT_VARIABLE_UNAVAILABLE" : "UNKNOWN_IMPORT_VARIABLE";
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                mvc.perform(post("/api/local-import/previews").locale(locale)
                                .contentType("application/json").content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value(code))
                        .andExpect(jsonPath("$.variable").value(variable))
                        .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages()
                                .get(locale, "local-import.error." + code, "{" + variable + "}")))
                        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("{" + variable + "}")))
                        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("{0}"))));
            }
        }
    }
    @Test @DisplayName("过期和繁忙错误有稳定状态，异常路径不回显")
    void safeErrors() throws Exception {
        when(service.scan(any())).thenThrow(new IllegalStateException("IMPORT_BUSY"));
        mvc.perform(post("/api/local-import/previews").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_BUSY"));
        UUID id = UUID.randomUUID(); when(service.status(id)).thenThrow(new IllegalArgumentException("IMPORT_TASK_EXPIRED"));
        mvc.perform(get("/api/local-import/tasks/" + id)).andExpect(status().isNotFound());
    }
}
