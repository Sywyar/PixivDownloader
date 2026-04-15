package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.quota.RateLimitService;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScriptController 单元测试")
class ScriptControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ScriptRegistry scriptRegistry;

    @Mock
    private RateLimitService rateLimitService;

    private static final String SCRIPT_CONTENT =
            """
                    // ==UserScript==
                    // @name         Test Script
                    // @version      1.0.0
                    // @description  Test
                    // @connect      i.pximg.net
                    // @connect      YOUR_SERVER_HOST
                    // ==/UserScript==
                    (function(){'use strict';})();""";

    private static final ScriptResource SAMPLE_RESOURCE = new ScriptResource(
            "test-script",
            "Test Script",
            "test.user.js",
            "Test",
            "1.0.0"
    );

    @BeforeEach
    void setUp() {
        when(rateLimitService.isAllowed(any())).thenReturn(true);
        ScriptController controller = new ScriptController(scriptRegistry, rateLimitService) {
            @Override
            protected String loadScriptContent(String fileName) {
                return SCRIPT_CONTENT;
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/scripts 返回非空列表，含预期 id")
    void listScripts_returnsExpectedId() throws Exception {
        when(scriptRegistry.getScripts()).thenReturn(List.of(SAMPLE_RESOURCE));

        mockMvc.perform(get("/api/scripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts", hasSize(1)))
                .andExpect(jsonPath("$.scripts[0].id", is("test-script")))
                .andExpect(jsonPath("$.scripts[0].displayName", is("Test Script")))
                .andExpect(jsonPath("$.detectedHost").exists());
    }

    @Test
    @DisplayName("GET /api/scripts/{id}/install 返回 200，Content-Type application/javascript，含脚本标记")
    void installScript_returnsJavascript() throws Exception {
        when(scriptRegistry.findById("test-script")).thenReturn(Optional.of(SAMPLE_RESOURCE));

        mockMvc.perform(get("/api/scripts/test-script/install"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/javascript")))
                .andExpect(content().string(containsString("// ==UserScript==")));
    }

    @Test
    @DisplayName("?raw=true 时 Content-Type 为 text/plain; charset=UTF-8")
    void installScript_rawParam_returnsTextPlain() throws Exception {
        when(scriptRegistry.findById("test-script")).thenReturn(Optional.of(SAMPLE_RESOURCE));

        mockMvc.perform(get("/api/scripts/test-script/install").param("raw", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")));
    }

    @Test
    @DisplayName("非 localhost 请求：YOUR_SERVER_HOST 被替换为实际 host")
    void installScript_nonLocalhost_replacesHost() throws Exception {
        when(scriptRegistry.findById("test-script")).thenReturn(Optional.of(SAMPLE_RESOURCE));

        mockMvc.perform(get("/api/scripts/test-script/install")
                        .with(req -> { req.setServerName("example.com"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("YOUR_SERVER_HOST"))))
                .andExpect(content().string(containsString("example.com")));
    }

    @Test
    @DisplayName("localhost 请求：保留 YOUR_SERVER_HOST 占位符")
    void installScript_localhost_keepsPlaceholder() throws Exception {
        when(scriptRegistry.findById("test-script")).thenReturn(Optional.of(SAMPLE_RESOURCE));

        mockMvc.perform(get("/api/scripts/test-script/install")
                        .with(req -> { req.setServerName("localhost"); return req; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("YOUR_SERVER_HOST")));
    }

    @Test
    @DisplayName("未知 id 返回 404")
    void installScript_unknownId_returns404() throws Exception {
        when(scriptRegistry.findById("no-such-id")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/scripts/no-such-id/install"))
                .andExpect(status().isNotFound());
    }
}
