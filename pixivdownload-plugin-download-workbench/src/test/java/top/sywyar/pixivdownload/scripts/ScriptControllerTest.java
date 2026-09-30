package top.sywyar.pixivdownload.scripts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptArtifact;
import top.sywyar.pixivdownload.plugin.api.userscript.UserscriptCatalog;

import java.util.List;
import java.net.URI;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScriptController 单元测试")
class ScriptControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserscriptCatalog userscriptCatalog;

    @Mock
    private NamespaceMessageResolver namespaceMessages;

    private static final String SCRIPT_CONTENT =
            """
                    // ==UserScript==
                    // @name         Test Script
                    // @version      1.0.0
                    // @updateURL    https://raw.githubusercontent.com/example/test.user.js
                    // @downloadURL  https://raw.githubusercontent.com/example/test.user.js
                    // @description  Test
                    // @connect      i.pximg.net
                    // @connect      YOUR_SERVER_HOST
                    // ==/UserScript==
                    (function(){'use strict';
                    // @pixiv-presence-bootstrap
                    window.originalScriptRan = true;
                    })();""";

    private static final UserscriptArtifact SAMPLE_ARTIFACT = new UserscriptArtifact(
            "test-script",
            "Test Script",
            "Test",
            "1.0.0",
            SCRIPT_CONTENT
    );

    @BeforeEach
    void setUp() {
        ScriptController controller = new ScriptController(
                userscriptCatalog,
                WorkbenchTestMessages.messages(), namespaceMessages
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/scripts 返回非空列表，含预期 id")
    void listScripts_returnsExpectedId() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts", hasSize(1)))
                .andExpect(jsonPath("$.scripts[0].id", is("test-script")))
                .andExpect(jsonPath("$.scripts[0].displayName", is("Test Script")))
                .andExpect(jsonPath("$.scripts[0].content").doesNotExist())
                .andExpect(jsonPath("$.detectedHost").exists());

        verify(userscriptCatalog).scripts();
        verifyNoMoreInteractions(userscriptCatalog);
    }

    @Test
    @DisplayName("插件贡献的翻译独立解析，缺失时回退为同一脚本快照的元数据")
    void contributedNamespaceLocalizesWithoutWorkbenchKeys() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(new UserscriptArtifact(
                "test-script", "Default name", "Default description", "1", SCRIPT_CONTENT, "third-party")));
        when(namespaceMessages.resolve(eq("third-party"), any(), eq("script.meta.test-script.name")))
                .thenReturn(java.util.Optional.of("独立脚本"));
        when(namespaceMessages.resolve(eq("third-party"), any(), eq("script.meta.test-script.description")))
                .thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/scripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts[0].displayName", is("独立脚本")))
                .andExpect(jsonPath("$.scripts[0].description", is("Default description")));
    }

    @Test
    @DisplayName("GET /api/scripts/{id}/install 返回 200，Content-Type application/javascript，含脚本标记")
    void installScript_returnsJavascript() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts/test-script/install"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/javascript")))
                .andExpect(content().string(containsString("// ==UserScript==")));
    }

    @Test
    @DisplayName("?raw=true 时 Content-Type 为 text/plain; charset=UTF-8")
    void installScript_rawParam_returnsTextPlain() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts/test-script/install").param("raw", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")));
    }

    @Test
    @DisplayName("GET /api/scripts/{id}?raw=true 无 .user.js 后缀时返回源码")
    void viewScriptSource_withoutUserJsSuffix_returnsTextPlain() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts/test-script").param("raw", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(content().string(containsString("// ==UserScript==")));
    }

    @Test
    @DisplayName("非 localhost 请求：YOUR_SERVER_HOST、updateURL 和 downloadURL 被替换")
    void installScript_nonLocalhost_replacesHost() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts/test-script/install")
                        .with(req -> {
                            req.setServerName("example.com");
                            req.setServerPort(6999);
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("YOUR_SERVER_HOST"))))
                .andExpect(content().string(containsString(
                        "// @updateURL    http://example.com:6999/api/scripts/test-script.user.js")))
                .andExpect(content().string(containsString(
                        "// @downloadURL  http://example.com:6999/api/scripts/test-script.user.js")))
                .andExpect(content().string(containsString("example.com")));
    }

    @Test
    @DisplayName("localhost 请求：保留 YOUR_SERVER_HOST，使用本机 updateURL")
    void installScript_localhost_keepsPlaceholder() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));

        mockMvc.perform(get("/api/scripts/test-script/install")
                        .with(req -> {
                            req.setServerName("localhost");
                            req.setServerPort(6999);
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("YOUR_SERVER_HOST")))
                .andExpect(content().string(containsString(
                        "// @updateURL    http://localhost:6999/api/scripts/test-script.user.js")))
                .andExpect(content().string(containsString(
                        "// @downloadURL  http://localhost:6999/api/scripts/test-script.user.js")));
    }

    @Test
    @DisplayName("未知 id 返回 404")
    void installScript_unknownId_returns404() throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of());

        mockMvc.perform(get("/api/scripts/no-such-id/install"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:6999", "http://127.0.0.1:8080", "https://example.com", "http://[::1]:6999"})
    @DisplayName("安装响应只增加安装站点下载页面的检测入口，并保留原脚本主体")
    void installedScriptBindsPresenceToItsOrigin(String origin) throws Exception {
        when(userscriptCatalog.scripts()).thenReturn(List.of(SAMPLE_ARTIFACT));
        String body = mockMvc.perform(get(URI.create(origin + "/api/scripts/test-script.user.js"))
                        .with(request -> {
                            URI address = URI.create(origin);
                            request.setServerPort(address.getPort() >= 0 ? address.getPort()
                                    : ("https".equals(address.getScheme()) ? 443 : 80));
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"origin\":\"" + origin + "\""), body);
        assertTrue(body.contains("\"id\":\"test-script\""));
        assertTrue(body.contains("GM_info"));
        assertTrue(body.contains("window.originalScriptRan = true;"));
        assertFalse(body.contains("// @pixiv-presence-bootstrap"));
        assertEquals(1, body.lines().filter(line -> line.startsWith("// @include ")).count());
        String include = body.lines().filter(line -> line.startsWith("// @include ")).findFirst().orElseThrow();
        String regex = include.substring("// @include /".length(), include.length() - 1);
        assertTrue((origin + "/pixiv-batch.html").matches(regex));
        assertTrue((origin + "/pixiv-batch-alt.html?lang=en").matches(regex));
        assertFalse((origin + "/other.html").matches(regex));
        assertFalse((origin + ".attacker.example/pixiv-batch.html").matches(regex));
        assertFalse(("https://attacker.example/?url=" + origin + "/pixiv-batch.html").matches(regex));
    }

    @Test
    @DisplayName("未声明检测入口的第三方脚本不增加运行站点或改变主体")
    void leavesNonParticipatingScriptsUnchanged() {
        String original = SCRIPT_CONTENT.replace("// @pixiv-presence-bootstrap", "");
        assertEquals(original, UserscriptPresenceInstaller.apply(original, "third-party",
                "http://localhost:6999/api/scripts/third-party.user.js"));
    }

    @Test
    @DisplayName("检测地址规范化默认端口并保留后端上下文路径")
    void normalizesDefaultPortAndContextPath() {
        String installed = UserscriptPresenceInstaller.apply(SCRIPT_CONTENT, "test-script",
                "https://EXAMPLE.com:443/downloads/api/scripts/test-script.user.js");
        assertTrue(installed.contains("\"origin\":\"https://example.com\""));
        assertTrue(installed.contains("\"page\":\"/downloads/pixiv-batch\""));
    }
}
