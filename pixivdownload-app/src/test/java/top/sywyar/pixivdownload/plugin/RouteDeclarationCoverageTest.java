package top.sywyar.pixivdownload.plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.ProgramPermission;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.setup.AuthFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全 URL 声明守卫：每个真实 controller 映射 / 静态资源目录 / 顶层 HTML 都必须落在某条
 * {@link WebRouteContribution}（带 {@code AccessPolicy}）里。
 * <p>
 * {@code AuthFilter} 对命中不了任何已声明路由的请求统一 404，因此「新增 / 遗漏一个 controller 映射或静态入口
 * 却没有对应的访问声明」必须让本测试失败——否则该 URL 在运行期会被 404、或回落到默认放行造成访问语义歧义。
 * 框架内部端点（如 Spring Boot 的 {@code /error}、actuator 的独立 handler mapping）不属业务路由、由内联分支
 * 或 ERROR dispatch 旁路处理，列入豁免集合。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false", "update.auto-check=false"
})
@DisplayName("全 URL 声明守卫：controller / 静态资源 / 顶层 HTML 均已声明 AccessPolicy")
class RouteDeclarationCoverageTest {

    /** 框架内部端点：非业务路由，由 ERROR dispatch / 独立 handler 旁路 AuthFilter，不要求 route 声明。 */
    private static final Set<String> FRAMEWORK_EXEMPT = Set.of("/error");

    private static final Map<String, String> originalProperties = new LinkedHashMap<>();

    static {
        Map.of(
                RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config",
                RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state",
                RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data").forEach((key, value) -> {
            originalProperties.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        originalProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Autowired
    private RouteAccessRegistry routeAccessRegistry;

    @Autowired private WebApplicationContext context;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private top.sywyar.pixivdownload.setup.SetupService setup;

    @Test
    @DisplayName("公开目录按实际方法、处理器条件及当前实例列出可用身份")
    void referenceSecurityMatchesEffectiveRouteAndHandler() throws Exception {
        org.mockito.Mockito.when(setup.isSetupComplete()).thenReturn(true);
        org.mockito.Mockito.when(setup.getMode()).thenReturn("solo");
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var response = mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn();
        var api = new JsonMapper().readTree(response.getResponse().getContentAsByteArray());
        var schemes = api.path("components").path("securitySchemes");
        assertThat(schemes.path("adminSessionCookie").path("name").asString()).isEqualTo(
                top.sywyar.pixivdownload.config.InstanceCookies.name(response.getRequest(), "pixiv_session"));
        assertThat(schemes.path("guestInviteCookie").path("name").asString()).isEqualTo(
                top.sywyar.pixivdownload.config.InstanceCookies.name(response.getRequest(), AuthFilter.INVITE_COOKIE));
        assertThat(schemes.path("guiToken").path("name").asString()).isEqualTo("X-GUI-Token");
        var paths = api.path("paths");
        assertThat(paths.path("/api/openapi.json").path("get").path("security").toString())
                .contains("programKey", "adminSessionHeader", "adminSessionCookie").doesNotContain("{}");
        assertThat(paths.path("/api/gui/status").path("get").path("security").toString())
                .isEqualTo("[{\"guiToken\":[]}]");
        assertThat(paths.path("/api/collections/memberships").path("post").path("security").toString())
                .contains("guestInviteCookie");
        assertThat(paths.path("/api/collections/{id}").path("delete").path("security").toString())
                .doesNotContain("guestInviteCookie", "{}");
        assertThat(paths.path("/api/downloaded/move/{artworkId}").path("post").path("security").toString())
                .isEqualTo("[{}]");
        assertThat(paths.path("/api/onboarding/profile").path("get").path("security").toString())
                .contains("adminSessionCookie", "{}");
        assertThat(paths.path("/api/onboarding/profile").path("post").path("security").toString())
                .contains("adminSessionCookie").doesNotContain("{}", "guestInviteCookie");
        assertThat(paths.path("/api/setup/status").path("get").path("security").toString()).doesNotContain("{}");
        org.mockito.Mockito.when(setup.getMode()).thenReturn("multi");
        var multi = new JsonMapper().readTree(mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("paths");
        assertThat(multi.path("/api/setup/status").path("get").path("security").toString()).contains("{}");
        assertThat(multi.path("/api/onboarding/profile").path("get").path("security").toString()).doesNotContain("{}");
    }

    @Test
    @DisplayName("接口参考向未登录的调用方说明程序密钥用法，目录接受声明了权限的程序密钥")
    void referenceCatalogExplainsAndAcceptsProgramKeys() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(AuthFilter.class)).build();
        var json = JsonMapper.builder().build();
        org.mockito.Mockito.when(setup.isSetupComplete()).thenReturn(true);
        org.mockito.Mockito.when(setup.isValidSession("reference-admin")).thenReturn(true);
        org.mockito.Mockito.when(setup.isAdminLoggedIn(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                "reference-admin".equals(invocation.<jakarta.servlet.http.HttpServletRequest>getArgument(0)
                        .getHeader("X-Session-Token")));

        // 不执行脚本也能读到的访问说明：目录地址、Bearer 用法、来自路由声明的权限 ID 与 service-desc 链接。
        mvc.perform(get("/openapi").param("lang", "en-US")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.allOf(containsString("<html lang=\"en-US\">"),
                                containsString("data-admin=\"false\""),
                                containsString("<code>GET /api/openapi.json</code>"),
                                containsString("<code>Authorization: Bearer &lt;key&gt;</code>"),
                                containsString("<code>core:api-reference.read</code>"),
                                containsString("rel=\"service-desc\""),
                                containsString("href=\"/login.html?redirect=%2Fopenapi\">sign in</a>"),
                                org.hamcrest.Matchers.not(containsString("{{")))));
        mvc.perform(get("/openapi").param("lang", "ja-JP")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("機械可読な OpenAPI ドキュメント")));

        mvc.perform(get("/openapi").header("X-Session-Token", "reference-admin").param("lang", "en-US"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("data-admin=\"true\"")));

        // 目录是程序资源：未登录时回 401 与 Bearer 质询，不跳转到带验证的登录页。
        mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"PixivDownloader\""))
                .andExpect(jsonPath("$.code").value("auth.unauthorized"))
                .andExpect(jsonPath("$.programPermission").value("core:api-reference.read"))
                .andExpect(jsonPath("$.error").value(containsString("core:api-reference.read")));
        mvc.perform(get("/api/openapi.json").accept("text/html"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"PixivDownloader\""))
                .andExpect(jsonPath("$.programPermission").value("core:api-reference.read"));

        var granted = createProgramKey(mvc, json, "core:api-reference.read");
        var other = createProgramKey(mvc, json, "core:application.read");
        try {
            var catalog = json.readTree(mvc.perform(get("/api/openapi.json").param("lang", "en-US")
                            .header("Authorization", "Bearer " + granted.path("secret").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertThat(catalog.path("openapi").asText()).startsWith("3.");
            assertThat(catalog.path("paths").path("/api/openapi.json").path("get").path("x-program-permission").asText())
                    .isEqualTo("core:api-reference.read");
            mvc.perform(get("/api/openapi.json").param("lang", "en-US")
                            .header("Authorization", "Bearer " + other.path("secret").asText()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("auth.program.forbidden"))
                    .andExpect(jsonPath("$.programPermission").value("core:api-reference.read"));
            var probe = catalog.path("paths").path("/api/openapi.json").path("head");
            assertThat(probe.path("x-program-permission").asText()).isEqualTo("core:api-reference.read");
            assertThat(probe.path("responses").path("401").path("headers").has("WWW-Authenticate")).isTrue();
            probe.path("responses").forEach(response -> assertThat(response.has("content")).isFalse());
            mvc.perform(head("/api/openapi.json").param("lang", "en-US")
                            .header("Authorization", "Bearer " + granted.path("secret").asText()))
                    .andExpect(status().isOk()).andExpect(header().string("Content-Language", "en-US"))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[0]));
            mvc.perform(head("/api/openapi.json").header("Authorization", "Bearer " + other.path("secret").asText()))
                    .andExpect(status().isForbidden());
            // 页面外壳仍只认原有身份，程序密钥不借公开页面扩大到其它资源。
            mvc.perform(get("/openapi").header("Authorization", "Bearer " + granted.path("secret").asText()))
                    .andExpect(status().isForbidden());
        } finally {
            for (var created : List.of(granted, other)) {
                var key = created.path("key");
                mvc.perform(delete("/api/security/program-keys/" + key.path("id").asText())
                                .header("X-Session-Token", "reference-admin")
                                .contentType("application/json")
                                .content("{\"revision\":" + key.path("revision").asLong() + "}"))
                        .andExpect(status().is2xxSuccessful());
            }
        }
    }

    @Test void toolProviderPermissionsMatchPublishedCatalog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var response = mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var spec = JsonMapper.shared().readTree(response.getContentAsByteArray());
        assertThat(spec.requiredAt("/x-program-permission-conditions/expression/contexts/query").asString()).isNotBlank();
        top.sywyar.pixivdownload.setup.ToolProviderPermissions.exercise(directory, spec.required("paths"));
    }

    @Test void malformedUpdateBodiesReturnTheDocumentedClientError() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String url : List.of("/api/schedule/tasks/17", "/api/collections/17")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                            .contentType("application/json").content("{"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("error.request.body.invalid"));
        }
    }

    private static tools.jackson.databind.JsonNode createProgramKey(
            org.springframework.test.web.servlet.MockMvc mvc, JsonMapper json, String permission) throws Exception {
        var body = json.writeValueAsString(Map.of("name", "reference " + permission,
                "permissions", List.of(permission), "anyIp", false, "networks", List.of("127.0.0.1/32")));
        return json.readTree(mvc.perform(post("/api/security/program-keys")
                        .header("X-Session-Token", "reference-admin")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void adminReferenceReflectsLiveRoutesAndManualRequestBodies() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(AuthFilter.class)).build();
        var json = JsonMapper.builder().build();
        var mapping = (PluginAwareRequestMappingHandlerMapping) requestMappingHandlerMapping;
        var path = "/api/reference-fixture/{id}";
        var info = RequestMappingInfo.paths(path)
                .methods(RequestMethod.GET).options(mapping.getBuilderConfiguration()).build();
        var route = WebRouteContribution.admin("/api/reference-fixture/*")
                .withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("read", "read")));
        org.mockito.Mockito.when(setup.isSetupComplete()).thenReturn(true);
        org.mockito.Mockito.when(setup.isValidSession("reference-admin")).thenReturn(true);
        var get = get("/api/openapi.json").param("lang", "en-US").header("X-Session-Token", "reference-admin");
        var ok = status().isOk();
        var noStore = header().string("Cache-Control", containsString("no-store"));
        var invite = new jakarta.servlet.http.Cookie("pixiv_invite_token", "invite");
        // 页面外壳与脚本公开，只有目录本身需要管理员会话或程序密钥。
        for (String resource : List.of("/openapi", "/openapi/ui.js", "/openapi/ui.css")) {
            mvc.perform(get(resource)).andExpect(ok).andExpect(noStore);
            mvc.perform(head(resource)).andExpect(ok);
            mvc.perform(get(resource).cookie(invite)).andExpect(ok);
            mvc.perform(get(resource).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
            mvc.perform(get(resource).header("X-Session-Token", "reference-admin")).andExpect(ok).andExpect(noStore);
        }
        var renderer = mvc.perform(get("/openapi/scalar.js")).andExpect(ok)
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andReturn().getResponse();
        String etag = renderer.getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get("/openapi/scalar.js").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[0]));
        mvc.perform(head("/openapi/scalar.js").cookie(invite)).andExpect(ok);
        // HEAD 与 GET 共用程序权限。
        mvc.perform(head("/api/openapi.json")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"PixivDownloader\""));
        mvc.perform(get("/api/openapi.json").cookie(invite)).andExpect(status().isForbidden());
        mvc.perform(get("/api/openapi.json").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("error=\"invalid_token\"")));
        mvc.perform(get("/api/openapi.json").header("X-Session-Token", "reference-admin")).andExpect(ok).andExpect(noStore);
        assertThat(routeAccessRegistry.isDeclared("/program-keys.html")).isFalse();
        assertThat(new ClassPathResource("static/program-keys.html").exists()).isFalse();
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/program-keys.html"))
                .andExpect(status().isNotFound());
        routeAccessRegistry.register("reference-fixture", List.of(route));
        try {
            mapping.registerPluginMapping("reference-fixture", info, new Fixture(), Fixture.class.getMethod("get", String.class));
            var english = mvc.perform(get).andExpect(ok).andReturn();
            var api = json.readTree(english.getResponse().getContentAsByteArray());
            var controller = context.getBean(top.sywyar.pixivdownload.openapi.ApiReferenceController.class);
            var englishLocale = java.util.Locale.forLanguageTag("en-US");
            var cached = controller.document(english.getRequest(), english.getResponse(), englishLocale);
            assertThat(api.path("openapi").asText()).startsWith("3.");
            var japanese = json.readTree(mvc.perform(get("/api/openapi.json").param("lang", "ja-JP")
                            .header("X-Session-Token", "reference-admin")).andExpect(ok)
                    .andReturn().getResponse().getContentAsByteArray());
            assertThat(japanese.path("info").path("description").asText()).contains("プログラム");
            assertThat(controller.document(english.getRequest(), english.getResponse(), englishLocale)).isSameAs(cached);
            assertThat(api.path("info").path("description").asText()).contains("program key").doesNotContain("{collection_name}");
            var paths = api.path("paths");
            var tags = new java.util.HashSet<String>();
            api.path("tags").forEach(tag -> tags.add(tag.path("name").asText()));
            assertThat(tags).contains("reference-fixture", "core-plugins", "core-collections");
            var catalog = api.path("tags").valueStream()
                    .filter(tag -> tag.path("name").asText().equals("work-template-variables"))
                    .findFirst().orElseThrow();
            assertThat(catalog.path("description").asText()).contains("{collection_name}", "{artwork_id}");
            var usedTags = new java.util.HashSet<String>();
            paths.forEach(item -> item.forEach(operation -> {
                operation.path("tags").forEach(tag -> usedTags.add(tag.asText()));
                operation.path("parameters").forEach(parameter ->
                        assertThat(parameter.path("name").asText()).isNotEqualTo("visibilityScope"));
            }));
            tags.remove("work-template-variables");
            assertThat(tags).isEqualTo(usedTags);
            assertThat(paths.path("/api/collections/{id}/icon").path("post")
                    .path("requestBody").path("content").has("multipart/form-data")).isTrue();
            assertThat(paths.path("/api/plugins/install").path("post").path("requestBody").path("content")
                    .path("multipart/form-data").path("schema").path("properties").path("file")
                    .path("description").asText()).contains("Plugin package to install");
            var deletion = paths.path("/api/collections/{id}").path("delete").path("responses");
            assertThat(deletion.has("204")).isTrue();
            assertThat(deletion.has("200")).isFalse();
            var enabled = paths.path("/api/plugins/{id}/enabled").path("put");
            var enabledJa = japanese.path("paths").path("/api/plugins/{id}/enabled").path("put");
            assertThat(enabled.path("summary").asText()).isEqualTo("Set plugin enablement");
            assertThat(enabledJa.path("summary").asText()).contains("プラグイン");
            assertThat(enabledJa.path("operationId")).isEqualTo(enabled.path("operationId"));
            assertThat(enabled.path("description").asText()).contains("lifecycle");
            assertThat(enabled.path("tags").get(0).asText()).isEqualTo("core-plugins");
            assertThat(enabled.path("responses").path("409").path("description").asText()).contains("required");
            var request = api.path("components").path("schemas").path("PluginEnabledRequest");
            var collection = api.path("components").path("schemas")
                    .path("top.sywyar.pixivdownload.collection.request.CollectionCreateRequest");
            var root = collection.path("properties").path("downloadRoot");
            assertThat(root.path("description").asText()).contains("{collection_name}",
                    "[" + catalog.path("x-displayName").asText() + "](#tag/work-template-variables)")
                    .doesNotContain("{collectionRootVariables}", "{artwork_id}");
            var scopes = api.path("x-work-template-variables");
            assertThat(scopes.path(root.path("x-work-template-scope").asText()).toString())
                    .isEqualTo("[\"{collection_name}\"]");
            assertThat(scopes.path("directory").toString()).contains("{collection_id}");
            assertThat(request.path("required").toString()).contains("enabled");
            assertThat(request.path("properties").path("enabled").path("description").asText()).contains("Use true to enable");
            assertThat(japanese.path("components").path("schemas").path("PluginEnabledRequest")
                    .path("properties").path("enabled").path("description").asText()).contains("有効にするには");
            assertThat(paths.path(path).path("get").path("x-program-permission").asText()).isEqualTo("reference-fixture:read");
            assertThat(paths.path("/api/admin/update/download").path("post").path("responses").has("202")).isTrue();
            assertThat(paths.path("/api/settings/validate").path("post").path("requestBody").path("content").has("application/json")).isTrue();
            assertThat(paths.path("/api/security/program-keys").path("post").has("x-program-permission")).isFalse();
            for (var entry : mapping.getHandlerMethods().keySet()) {
                for (var pattern : entry.getPatternValues()) {
                    if (!pattern.startsWith("/api/") || pattern.contains("*") || pattern.contains(":")) continue;
                    for (var verb : entry.getMethodsCondition().getMethods())
                        assertThat(paths.path(pattern).has(verb.name().toLowerCase(java.util.Locale.ROOT)))
                                .as("%s %s", verb, pattern).isTrue();
                }
            }
        } finally {
            mapping.unregisterMapping(info);
            routeAccessRegistry.unregister("reference-fixture");
        }
        assertThat(json.readTree(mvc.perform(get).andExpect(ok).andReturn().getResponse().getContentAsByteArray())
                .path("paths").has(path)).isFalse();
    }

    @Test void submissionCatalogFollowsProviderPublicationWithoutAMappingChange() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var request = get("/api/openapi.json").param("lang", "en-US");
        var registry = context.getBean(DownloadLifecycleRegistry.class);
        var owner = new ExternalCapabilityOwner("reference-submission", "reference-submission", 1, 1);
        String schema = """
                {"type":"object","properties":{"workType":{"const":"fixture"}},
                 "examples":[{"workType":"fixture","workId":"12"}]}
                """;
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$['x-download-submissions'].fixture").doesNotExist());
        try {
            var handler = org.mockito.Mockito.mock(DownloadSubmissionHandler.class);
            registry.register(owner, new DownloadLifecycleRegistry.Contribution(List.of(), List.of(), List.of(),
                    Map.of("fixture", handler, "legacy-example", handler), Map.of("fixture", schema)));
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$['x-download-submissions']['legacy-example']['x-input-schema-available']").value(false))
                    .andExpect(jsonPath("$['x-download-submissions'].fixture['x-program-permission']").value("reference-submission:downloads.create"))
                    .andExpect(jsonPath("$['x-download-submissions'].fixture.properties.workType.const").value("fixture"))
                    .andExpect(jsonPath("$.components.schemas['DownloadSubmission.fixture'].properties.workType.const").value("fixture"))
                    .andExpect(jsonPath("$.paths['/api/download/tasks'].post.requestBody.content['application/json'].examples.fixture.value.workId").value("12"));
        } finally {
            registry.withdraw(owner);
        }
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$['x-download-submissions'].fixture").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/download/tasks'].post.requestBody.content['application/json'].examples.fixture").doesNotExist());
    }

    @Test void scheduleCatalogFollowsActivePublicationsWithoutExposingActivationTokens() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var request = get("/api/openapi.json").param("lang", "en-US");
        var registry = context.getBean(ScheduleCapabilityRegistry.class);
        var json = JsonMapper.shared();
        String path = "$['x-schedule-sources']['reference-source']";
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath(path).doesNotExist());
        String schema = """
                {"$defs":{"name":{"type":"string"}},"type":"object","properties":{"label":{"$ref":"#/$defs/name"}},
                 "required":["label"],"examples":[{"label":"first"}]}
                """;
        var first = publishScheduleFixture(registry, 1, schema);
        try {
            var api = json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            var catalog = api.path("x-schedule-sources");
            var example = api.at("/paths/~1api~1schedule~1tasks/post/requestBody/content/application~1json/examples/reference-source/value");
            assertThat(example.path("sourceType").asString()).isEqualTo("reference-source");
            assertThat(json.readTree(example.path("definitionJson").asString()).path("label").asString()).isEqualTo("first");
            var batch = api.at("/paths/~1api~1download~1batches/post/requestBody/content/application~1json/examples/reference-source/value");
            assertThat(batch.at("/definition/label").asString()).isEqualTo("first");
            String component = "/components/schemas/ScheduleDefinition.reference-source";
            assertThat(api.at(component + "/properties/label/$ref").asString()).isEqualTo("#" + component + "/$defs/name");
            assertThat(api.at(component + "/$defs/name/type").asString()).isEqualTo("string");
            var create = api.at("/components/schemas/top.sywyar.pixivdownload.schedule.batch.ManualBatchController.Create");
            String legacy = create.at("/discriminator/mapping/legacy-reference-source").asString();
            assertThat(create.at("/discriminator/propertyName").asString()).isEqualTo("sourceType");
            assertThat(create.path("oneOf").valueStream().anyMatch(v -> legacy.equals(v.at("/$ref").asString()))).isTrue();
            var legacyRequest = api.at(legacy.substring(1));
            assertThat(legacyRequest.at("/properties/definition/type").asString()).isEqualTo("object");
            assertThat(legacyRequest.at("/properties/definition").has("$ref")).isFalse();
            assertThat(legacyRequest.at("/properties/sourceType/const").asString()).isEqualTo("legacy-reference-source");
            var definition = api.path("components").path("schemas").path("top.sywyar.pixivdownload.schedule.batch.ManualBatchController.Create")
                    .at("/properties/definition");
            assertThat(definition.path("type").asString()).as("batch definition schema %s", definition).isEqualTo("object");
            assertThat(definition.path("description").asString()).contains("x-schedule-sources");
            var entry = catalog.path("reference-source");
            assertThat(entry.path("sourceType").asString()).isEqualTo("reference-source");
            assertThat(entry.path("ownerPluginId").asString()).isEqualTo("reference-owner");
            assertThat(entry.path("definitionSchema").asString()).isEqualTo("reference.definition");
            assertThat(entry.path("definitionVersion").asInt()).isEqualTo(1);
            var input = json.readTree(entry.required("inputSchemaJson").asString());
            assertThat(java.net.URI.create(input.path("$id").asString()).isAbsolute()).isTrue();
            String ref = input.at("/properties/label/$ref").asString();
            assertThat(input.at(ref.substring(1)).path("type").asString()).isEqualTo("string");
            assertThat(input.at("/examples/0/label").asString()).isEqualTo("first");
            assertThat(catalog.path("legacy-reference-source").path("inputSchemaJson").isNull()).isTrue();
            assertThat(catalog.path("malformed-reference-source").path("inputSchemaJson").isNull()).isTrue();
            var manifest = json.readTree(mvc.perform(get("/api/schedule/sources"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            var published = manifest.path("sources").valueStream()
                    .filter(source -> source.path("sourceType").asString().equals("reference-source")).findFirst().orElseThrow();
            assertThat(api.toString()).doesNotContain(published.path("activationToken").asString());
            assertThat(api.path("components").path("schemas").path("top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest")
                    .at("/properties/definitionJson/description").asString()).contains("x-schedule-sources");
        } finally {
            ScheduleCapabilityRegistryTestAccess.withdraw(registry, first).orElseThrow();
        }
        var next = publishScheduleFixture(registry, 2, """
                {"$id":"urn:example:reference:second","type":"object","examples":[{"changed":true}]}
                """);
        try {
            var response = mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath(path + ".definitionVersion").value(2)).andReturn().getResponse();
            var updated = json.readTree(json.readTree(response.getContentAsByteArray())
                    .at("/x-schedule-sources/reference-source/inputSchemaJson").asString());
            assertThat(updated.path("$id").asString()).isEqualTo("urn:example:reference:second");
            assertThat(updated.at("/examples/0/changed").asBoolean()).isTrue();
        } finally {
            ScheduleCapabilityRegistryTestAccess.withdraw(registry, next).orElseThrow();
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath(path).doesNotExist());
    }

    private static ScheduleCapabilityPublication publishScheduleFixture(ScheduleCapabilityRegistry registry,
                                                                        int version, String schema) {
        var descriptors = new ArrayList<ScheduledSourceDescriptor>();
        var executors = new ArrayList<ScheduledSourceExecutor>();
        for (String type : List.of("reference-source", "legacy-reference-source", "malformed-reference-source")) {
            String input = switch (type) {
                case "reference-source" -> schema;
                case "malformed-reference-source" -> "[";
                default -> null;
            };
            descriptors.add(new ScheduledSourceDescriptor(type, Set.of(), "reference.definition", version,
                    new ScheduledSourcePresentation("reference", "name", "description", "schedule", "neutral"),
                    Set.of(), Set.of("reference-work"), Set.of(), Set.of(), null, input));
            var executor = org.mockito.Mockito.mock(ScheduledSourceExecutor.class);
            org.mockito.Mockito.when(executor.sourceType()).thenReturn(type);
            executors.add(executor);
        }
        var owner = new ScheduleCapabilityOwner("reference-owner", "reference-package", version);
        return ScheduleCapabilityRegistryTestAccess.publish(registry,
                ScheduleOwnerBundle.prepare(owner, descriptors, executors, List.of(), List.of(), List.of()));
    }

    @Test void programKeyLastUseDocumentsInstanceLifetimeInEachLocale() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var bundles = Map.of("zh-CN", "", "en-US", "_en", "zh-Hant", "_zh-Hant", "ja-JP", "_ja", "ko-KR", "_ko");
        for (var locale : bundles.entrySet()) {
            var messages = new java.util.Properties();
            var bundle = new ClassPathResource("i18n/http/core-keys" + locale.getValue() + ".properties");
            try (var reader = new java.io.InputStreamReader(bundle.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            var spec = JsonMapper.shared().readTree(mvc.perform(get("/api/openapi.json").param("lang", locale.getKey()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            for (String model : List.of("ProgramKeyStore.View", "ProgramKeysController.KeyRow")) {
                var field = spec.requiredAt("/components/schemas/top.sywyar.pixivdownload.setup." + model + "/properties/lastUsedAt");
                var expected = messages.getProperty("schema.top.sywyar.pixivdownload.setup." + model + ".field.lastUsedAt");
                assertThat(field.required("description").asString()).as("%s in %s", model, locale.getKey())
                        .contains("Unix", "null").isEqualTo(expected);
            }
        }
    }

    @Test
    void documentsArchiveDeletionOutcomeAndCommittedCountsInEveryLocale() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var bundles = Map.of("zh-CN", "", "en-US", "_en", "zh-Hant", "_zh-Hant", "ja-JP", "_ja", "ko-KR", "_ko");
        String model = "top.sywyar.pixivdownload.quota.response.";
        String result = model + "ArchiveStatusResponse.SourceDeletion";
        for (var locale : bundles.entrySet()) {
            var labels = new java.util.Properties();
            var bundle = new ClassPathResource("i18n/http/core-archives" + locale.getValue() + ".properties");
            try (var reader = new java.io.InputStreamReader(bundle.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                labels.load(reader);
            }
            var spec = JsonMapper.shared().readTree(mvc.perform(get("/api/openapi.json").param("lang", locale.getKey()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            var schemas = spec.requiredAt("/components/schemas");
            for (String owner : List.of("ArchiveStatusResponse", "AdminArchiveTasksResponse.Task")) {
                assertThat(schemas.required(model + owner).requiredAt("/properties/sourceDeletion").toString())
                        .contains("#/components/schemas/" + result);
            }
            var fields = schemas.required(result).required("properties");
            assertThat(fields.requiredAt("/status/enum").values().stream().map(node -> node.asString()).toList())
                    .containsExactlyInAnyOrder("not_requested", "pending", "running", "not_started", "succeeded", "partial", "failed");
            for (String field : List.of("status", "requestedWorks", "deletedWorks")) {
                assertThat(fields.required(field).required("description").asString()).isEqualTo(
                        labels.getProperty("schema." + result + ".field." + field));
            }
        }
    }

    @Test void taskErrorSchemasAcceptActualCodesAndRejectAnUnknownCodeWhenClosed() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var json = JsonMapper.shared();
        var spec = json.readTree(mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
        for (String route : List.of("/api/tools/backfill/tasks/{id}", "/api/local-import/previews/{id}")) {
            var actual = mvc.perform(get(route, java.util.UUID.randomUUID())).andExpect(status().isNotFound())
                    .andReturn().getResponse();
            var body = (tools.jackson.databind.node.ObjectNode) json.readTree(actual.getContentAsByteArray());
            var document = json.createObjectNode();
            document.set("components", spec.required("components"));
            document.set("allOf", json.createArrayNode().add(spec.required("paths").required(route).required("get")
                    .required("responses").required("404").required("content").required("application/json").required("schema")));
            var responseSchema = registry.getSchema(document);
            assertThat(responseSchema.validate(body)).as(route).isEmpty();
            body.put("code", "UNKNOWN_TASK_ERROR");
            assertThat(responseSchema.validate(body)).as(route).isNotEmpty();
        }
    }

    @Test void generatedScheduleAndBatchErrorsMatchTheirHttpResponses() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var spec = JsonMapper.shared().readTree(mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var paths = spec.path("paths");
        var taskErrors = paths.path("/api/schedule/tasks").path("post").path("responses");
        var batchErrors = paths.path("/api/download/batches/{id}").path("get").path("responses");
        var credentialErrors = paths.path("/api/download/batches/{id}/credentials").path("put").path("responses");
        var typedErrors = List.of(taskErrors.path("400"), taskErrors.path("409"), taskErrors.path("503"),
                batchErrors.path("404"), credentialErrors.path("413"));
        for (var documented : typedErrors) {
            var schema = documented.path("content").path("application/json").path("schema");
            assertThat(hasErrorField(spec, schema, "code")).as("code in %s", schema).isTrue();
            assertThat(hasErrorField(spec, schema, "error")).as("error in %s", schema).isTrue();
        }
        assertThat(taskErrors.path("400").path("description").asString()).contains("schedule.error.definition-invalid");
        assertThat(taskErrors.path("409").path("description").asString()).contains("schedule.error.source-publication-changed");
        assertThat(taskErrors.path("503").path("description").asString()).contains("DATABASE_UNAVAILABLE");
        mvc.perform(post("/api/schedule/tasks").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("error.request.validation"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        var id = java.util.UUID.randomUUID();
        mvc.perform(get("/api/download/batches/{id}", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("batch.not-found"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/download/batches/{id}/credentials", id).contentType("application/json")
                        .content(" ".repeat(16 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("HTTP_BODY_LIMIT"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    private static boolean hasErrorField(tools.jackson.databind.JsonNode document, tools.jackson.databind.JsonNode schema,
                                         String field) {
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asString();
            assertThat(ref).startsWith("#/components/schemas/");
            return hasErrorField(document, document.at(ref.substring(1)), field);
        }
        if (schema.has("anyOf")) {
            var variants = schema.path("anyOf").values();
            return !variants.isEmpty() && variants.stream().allMatch(variant -> hasErrorField(document, variant, field));
        }
        return schema.path("properties").has(field)
                || schema.path("allOf").values().stream().anyMatch(base -> hasErrorField(document, base, field));
    }

    @Test void catalogExplainsControllerAndFilterContractsAtTheHttpBoundary() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var response = mvc.perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var api = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        var paths = api.path("paths");
        var missing = new ArrayList<String>();
        for (var path : paths.properties()) {
            for (var method : path.getValue().properties()) {
                var operation = method.getValue();
                if (!operation.has("operationId")) continue;
                String where = method.getKey() + " " + path.getKey();
                if (operation.path("summary").asText("").isBlank()
                        || operation.path("description").asText("").isBlank()) missing.add(where);
                for (var parameter : operation.path("parameters")) {
                    // Reusable header definitions live in components.parameters.
                    if (parameter.has("$ref")) parameter = api.at(parameter.path("$ref").asText().substring(1));
                    if (parameter.path("description").asText("").isBlank()) missing.add(where + " " + parameter.path("name"));
                }
                if (operation.has("requestBody") && operation.path("requestBody").path("description").asText("").isBlank())
                    missing.add(where + " requestBody");
                for (var result : operation.path("responses").properties()) {
                    if (result.getValue().path("description").asText("").isBlank()) missing.add(where + " " + result.getKey());
                }
            }
        }
        var models = api.path("components").path("schemas");
        var taskResponses = paths.path("/api/schedule/tasks").path("post").path("responses");
        assertThat(taskResponses.path("200").path("content").has("application/json")).isTrue();
        assertThat(taskResponses.path("400").path("content").path("application/json").toString())
                .contains("ApiErrorResponse.Basic");
        assertThat(taskResponses.path("401").path("headers").has("WWW-Authenticate")).isTrue();
        assertThat(taskResponses.path("403").path("content").path("application/json").toString())
                .contains("ProgramCredentialError");
        var nullableTask = models.path("top.sywyar.pixivdownload.gui.StartupCacheController.Status")
                .path("properties").path("task").path("anyOf");
        assertThat(nullableTask.valueStream().anyMatch(v -> "null".equals(v.path("type").asText("")))).isTrue();
        assertThat(nullableTask.valueStream().anyMatch(v -> v.path("$ref").asText("").endsWith("DesktopStartupCache.Snapshot"))).isTrue();
        assertThat(taskResponses.path("405").path("headers").has("Allow")).isTrue();
        var methodError = taskResponses.path("405").at("/content/application~1json/schema/$ref").asString();
        assertThat(api.at(methodError.substring(1)).at("/allOf/1/properties/code/enum/0").asString())
                .isEqualTo("http.status.405");
        assertThat(taskResponses.path("415").path("content").has("application/json")).isTrue();
        var ffmpegPath = models.path("top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation")
                .path("properties").path("ffmpegPath");
        assertThat(ffmpegPath.path("format").asText()).isEqualTo("uri");
        assertThat(ffmpegPath.has("properties")).isFalse();
        for (var model : models.properties()) {
            for (var field : model.getValue().path("properties").properties()) {
                if (field.getValue().path("description").asText("").isBlank()) {
                    missing.add(model.getKey() + "." + field.getKey());
                }
            }
        }
        assertThat(missing).as("Undocumented parts of the published API").isEmpty();
        var prepare = paths.path("/api/operation-hooks/prepare").path("post");
        assertThat(prepare.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/HookPreparation");
        assertThat(models.path("HookPreparation").path("properties").has("resolveOnly")).isTrue();
        var parameters = api.path("components").path("parameters");
        assertThat(parameters.has("X-Pixiv-Hook-Ticket")).isTrue();
        assertThat(paths.path("/api/download/tasks").path("post").path("parameters").toString())
                .contains("X-Acquisition-Credentials");
        assertThat(paths.path("/api/collections").path("get").path("parameters").toString())
                .doesNotContain("X-Acquisition-Credentials");
        var variables = api.path("x-work-template-variable-descriptions");
        var settings = paths.path("/api/settings");
        assertThat(settings.path("get").path("parameters").toString()).doesNotContain("\"Origin\"", "\"Referer\"");
        var write = settings.path("post");
        var origin = write.path("parameters").valueStream().filter(p -> p.path("name").asText().equals("Origin")).findFirst().orElseThrow();
        assertThat(origin.path("required").asBoolean()).isFalse();
        assertThat(origin.path("description").asText()).contains("CSRF", "Bearer", "cookie");
        assertThat(write.path("parameters").toString()).contains("\"Referer\"");
        assertThat(write.path("responses").path("403").toString()).contains("auth.csrf.invalid", "ApiErrorResponse.Basic");
        var schedule = paths.path("/api/schedule/tasks").path("post").path("parameters");
        assertThat(schedule.toString()).contains("even for a Bearer client");
        for (var scope : api.path("x-work-template-variables")) {
            for (var token : scope) assertThat(variables.path(token.asText()).asText()).isNotBlank().isNotEqualTo(token.asText());
        }
    }

    static final class Fixture {
        public String get(@PathVariable String id) { return id; }
    }

    @Test
    @DisplayName("全部 @RestController / @Controller 映射 URL（含 HTTP 方法）都被某条 route 声明覆盖")
    void everyControllerMappingIsDeclared() {
        assertThat(requestMappingHandlerMapping.getHandlerMethods()).as("应能枚举到 controller 映射").isNotEmpty();

        List<String> undeclared = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .forEach(info -> {
                    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                    for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                        if (FRAMEWORK_EXEMPT.contains(pattern)) {
                            continue;
                        }
                        String concrete = toConcretePath(pattern);
                        if (methods.isEmpty()) {
                            // 无方法限定（响应全部方法）：按项目约定以 GET 代表验证「至少有声明覆盖」。
                            if (!routeAccessRegistry.isDeclared(concrete, HttpMethod.GET)) {
                                undeclared.add("* " + pattern);
                            }
                        } else {
                            for (RequestMethod rm : methods) {
                                HttpMethod hm = toHttpMethod(rm);
                                if (hm != null && !routeAccessRegistry.isDeclared(concrete, hm)) {
                                    undeclared.add(rm + " " + pattern);
                                }
                            }
                        }
                    }
                });

        assertThat(undeclared)
                .as("以下 controller URL（方法）未在任何插件 routes() 以 AccessPolicy 声明 —— "
                        + "新增 controller 必须同步声明其路由访问策略（含 HTTP 方法），否则运行期请求会被 404：%s", undeclared)
                .isEmpty();
    }

    /** Spring {@link RequestMethod} → 本项目 {@link HttpMethod}；未建模的方法（如 TRACE）返回 null（跳过）。 */
    private static HttpMethod toHttpMethod(RequestMethod requestMethod) {
        try {
            return HttpMethod.valueOf(requestMethod.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Test
    @DisplayName("每个声明的静态资源目录都有对应的 route 访问声明覆盖")
    void everyStaticResourceDirIsDeclared() {
        List<String> undeclared = BuiltInPlugins.createAll().stream()
                .flatMap(plugin -> plugin.staticResources().stream())
                .map(StaticResourceContribution::publicPathPrefix)
                .distinct()
                .filter(prefix -> !routeAccessRegistry.isDeclared(prefix + "__coverage_probe__"))
                .toList();

        assertThat(undeclared)
                .as("以下 serving 静态目录前缀的访问未由任何 route 声明覆盖 —— "
                        + "静态目录的 serving 与访问必须同时声明：%s", undeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("每个顶层静态 HTML 页面都被某条 route 声明覆盖")
    void everyTopLevelHtmlPageIsDeclared() throws IOException {
        Resource[] pages = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/static/*.html");
        assertThat(pages).as("应能枚举到顶层静态 HTML 页面").isNotEmpty();

        List<String> undeclared = new java.util.ArrayList<>();
        for (Resource page : pages) {
            String name = page.getFilename();
            if (name == null) {
                continue;
            }
            String path = "/" + name;
            if (!routeAccessRegistry.isDeclared(path)) {
                undeclared.add(path);
            }
        }

        assertThat(undeclared)
                .as("以下顶层 HTML 页面未在任何插件 routes() 以 AccessPolicy 声明：%s", undeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("内置插件清单与运行期注册一致（守卫使用运行期 registry 反映实际启用插件）")
    void runtimeRegistryReflectsBuiltInPlugins() {
        // 运行期 registry（@Autowired，反映实际注册）应至少覆盖内置插件清单等价快照的全部声明，
        // 否则本守卫的覆盖结论与运行期不一致。逐条核对内置快照的每条声明在运行期 registry 中也存在。
        RouteAccessRegistry builtIn = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        Set<String> runtimeKeys = routeAccessRegistry.routes().stream()
                .map(r -> r.route().pathPattern() + "|" + r.route().accessPolicy())
                .collect(Collectors.toSet());
        List<String> missing = builtIn.routes().stream()
                .map(r -> r.route().pathPattern() + "|" + r.route().accessPolicy())
                .filter(key -> !runtimeKeys.contains(key))
                .toList();
        assertThat(missing).as("运行期 registry 缺失内置快照声明：%s", missing).isEmpty();
    }

    /** 把含 {@code {placeholder}} 的映射模式转成一条具体路径，供 registry 的 startsWith / 精确匹配判定。 */
    private static String toConcretePath(String pattern) {
        return pattern.replaceAll("\\{[^/]+}", "x");
    }
}
