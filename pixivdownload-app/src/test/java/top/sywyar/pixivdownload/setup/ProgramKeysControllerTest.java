package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("程序密钥管理的真实 HTTP 序列化与保存边界")
class ProgramKeysControllerTest {
    @TempDir Path directory;
    private MockMvc mvc;
    private SetupService setup;
    private ProgramKeyService keys;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void initialize() {
        var plugin = mock(PixivFeaturePlugin.class);
        when(plugin.id()).thenReturn("example");
        when(plugin.displayName()).thenReturn("Legacy Plugin");
        when(plugin.routes()).thenReturn(List.of(WebRouteContribution.admin("/api/example").withProgramPermissions(
                Map.of(HttpMethod.GET, new ProgramPermission("read", "permission.read")))));
        var plugins = new PluginRegistry(List.of(plugin));
        keys = new ProgramKeyService(directory.resolve("keys.json"), new RouteAccessRegistry(plugins), plugins);
        setup = mock(SetupService.class);
        when(setup.isAdminLoggedIn(any())).thenReturn(true);
        var messages = TestI18nBeans.appMessages();
        mvc = MockMvcBuilders.standaloneSetup(new ProgramKeysController(keys, setup, messages,
                        new ToolApiRequests(messages, mock(WebI18nService.class)), () -> "desktop-test-token",
                        (namespace, locale, key) -> Optional.empty()))
                .setControllerAdvice(new GlobalExceptionHandler(messages)).build();
    }

    @Test @DisplayName("完整密钥只出现在成功创建响应，修订冲突不覆盖授权")
    void createEditAndRevoke() throws Exception {
        String policy = "{\"name\":\"worker\",\"permissions\":[\"example:read\"],\"anyIp\":false,\"networks\":[\"192.0.2.0/24\"]}";
        var created = json.readTree(mvc.perform(post("/api/security/program-keys").contentType("application/json").content(policy))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
        String secret = created.path("secret").asText();
        String id = created.path("key").path("id").asText();
        assertThat(keys.authenticate(secret, "192.0.2.1", true)).isNotNull();
        assertThat(Files.readString(directory.resolve("keys.json"))).doesNotContain(secret);
        var listing = mvc.perform(get("/api/security/program-keys")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secret").doesNotExist()).andExpect(jsonPath("$[0].digest").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(secret);
        String edited = policy.replace("192.0.2.0/24", "198.51.100.0/24");
        mvc.perform(put("/api/security/program-keys/" + id).contentType("application/json")
                .content("{\"revision\":1,\"policy\":" + edited + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        assertThat(keys.authenticate(secret, "192.0.2.1", true)).isNull();
        assertThat(keys.authenticate(secret, "198.51.100.1", true)).isNotNull();
        mvc.perform(delete("/api/security/program-keys/" + id).contentType("application/json").content("{\"revision\":1}"))
                .andExpect(status().isConflict());
        assertThat(keys.authenticate(secret, "198.51.100.1", true)).isNotNull();
        mvc.perform(delete("/api/security/program-keys/" + id).contentType("application/json").content("{\"revision\":2}"))
                .andExpect(status().isNoContent());
        assertThat(keys.authenticate(secret, "198.51.100.1", true)).isNull();
    }

    @Test void selfReturnsOnlyTheAuthenticatedPublicIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get(ProgramAccess.SELF_PATH).requestAttr(ProgramIdentity.REQUEST_ATTRIBUTE,
                        new ProgramIdentity(id, Set.of("example:read"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"id\":\"" + id + "\",\"permissions\":[\"example:read\"]}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test void desktopEditsAndRevokesWithItsOwnCredential() throws Exception {
        when(setup.isAdminLoggedIn(any())).thenReturn(false);
        String base = "/api/gui/program-keys";
        String header = top.sywyar.pixivdownload.common.GuiTokenProvider.HEADER_NAME;
        String policy = "{\"name\":\"desktop\",\"permissions\":[],\"anyIp\":true,\"networks\":[]}";
        mvc.perform(post(base).header(header, "wrong").contentType("application/json").content(policy))
                .andExpect(status().isForbidden());
        mvc.perform(post(base).with(req -> { req.setRemoteAddr("192.0.2.1"); return req; })
                .header(header, "desktop-test-token").contentType("application/json").content(policy)).andExpect(status().isForbidden());
        mvc.perform(get(base + "/permissions").header(header, "desktop-test-token"))
                .andExpect(jsonPath("$[0].ownerLabel").value("Legacy Plugin"))
                .andExpect(jsonPath("$[0].label").value("example:read"));
        var created = mvc.perform(post(base).header(header, "desktop-test-token").contentType("application/json").content(policy))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String item = base + "/" + json.readTree(created.getContentAsByteArray()).path("id").asText();
        mvc.perform(get(base).header(header, "desktop-test-token")).andExpect(jsonPath("$[0].name").value("desktop"))
                .andExpect(jsonPath("$[0].permissionCount").value(0)).andExpect(jsonPath("$[0].policy").doesNotExist());
        mvc.perform(get(item).header(header, "desktop-test-token"))
                .andExpect(jsonPath("$.policy.name").value("desktop")).andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(post(item + "/update").header(header, "desktop-test-token").contentType("application/json")
                .content("{\"revision\":1,\"policy\":" + policy + "}")).andExpect(status().isNoContent());
        mvc.perform(post(item + "/revoke").header(header, "desktop-test-token").contentType("application/json")
                .content("{\"revision\":2}")).andExpect(status().isNoContent());
        assertThat(keys.list()).isEmpty();
    }

    @Test @DisplayName("名称、权限和来源分别返回可纠正的错误")
    void validationIdentifiesTheInvalidField() throws Exception {
        var cases = java.util.Map.of(
                "name-invalid", "{\"name\":\"\",\"permissions\":[],\"anyIp\":true,\"networks\":[]}",
                "permissions-invalid", "{\"name\":\"worker\",\"permissions\":[\"missing:permission\"],\"anyIp\":true,\"networks\":[]}",
                "networks-invalid", "{\"name\":\"worker\",\"permissions\":[],\"anyIp\":false,\"networks\":[\"192.0.2.1/999\"]}");
        for (var item : cases.entrySet()) {
            mvc.perform(post("/api/security/program-keys").locale(java.util.Locale.US)
                    .contentType("application/json").content(item.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("auth.program." + item.getKey()))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("192.0.2.1/999"))));
        }
        assertThat(keys.list()).isEmpty();
    }

    @Test @DisplayName("无管理员会话时不能读取或创建密钥，非法输入不产生授权")
    void rejectsWithoutChangingStore() throws Exception {
        when(setup.isAdminLoggedIn(any())).thenReturn(false);
        mvc.perform(get("/api/security/program-keys")).andExpect(status().isForbidden());
        mvc.perform(post("/api/security/program-keys").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertThat(Files.exists(directory.resolve("keys.json"))).isFalse();
        when(setup.isAdminLoggedIn(any())).thenReturn(true);
        for (String body : List.of("null", "{}", "{\"name\":\"worker\",\"permissions\":[\"example:future\"],\"anyIp\":true,\"networks\":[]}",
                "{\"name\":\"worker\",\"permissions\":[],\"anyIp\":false,\"networks\":[]}")) {
            mvc.perform(post("/api/security/program-keys").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(keys.list()).isEmpty();
        assertThat(Files.exists(directory.resolve("keys.json"))).isFalse();
    }
}
