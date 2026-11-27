package top.sywyar.pixivdownload.setup;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.gui.DesktopToolHistory;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import top.sywyar.pixivdownload.gui.importing.FileImportToolController;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.gui.media.MediaToolController;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 按实际目录调用两种工具，只给程序身份签发测试所需的授权。 */
public final class ToolProviderPermissions {
    private static final ExternalCapabilityOwner OWNER = new ExternalCapabilityOwner("independent-tool", "fixture-package", 2, 7);
    private static final Map<String, Object> ID = Map.of("pluginId", OWNER.pluginId(), "packageId", OWNER.packageId(),
            "generation", 2, "publication", 7);

    private ToolProviderPermissions() {}

    public static void exercise(Path directory, JsonNode paths) throws Exception {
        var feature = mock(PixivFeaturePlugin.class);
        when(feature.id()).thenReturn(OWNER.pluginId());
        var plugins = new PluginRegistry(List.of(new CorePlugin(), feature));
        var routes = new RouteAccessRegistry(plugins);
        var media = new DesktopMediaToolRegistry();
        var imports = new DesktopFileImportToolRegistry();
        var label = DesktopUiText.key("tool");
        var mediaSource = mock(DesktopMediaTool.Source.class);
        var idle = new DesktopMediaTool.Status("idle", 0, 0, 0, List.of());
        when(mediaSource.status()).thenReturn(idle);
        when(mediaSource.preview(any())).thenReturn(new DesktopMediaTool.Result<>(
                new DesktopMediaTool.Preview("token", List.of(), 0, 0, false), null));
        when(mediaSource.start("token")).thenReturn(new DesktopMediaTool.Result<>(idle, null));
        when(mediaSource.capabilities()).thenReturn(new DesktopMediaTool.Result<>(new DesktopMediaTool.Report("", "SYSTEM", List.of()), null));
        media.register(OWNER, new DesktopMediaTool.Description(label, "fixture", "png", "gif"), mediaSource);
        var importSource = mock(DesktopFileImportTool.Source.class);
        var importIdle = new DesktopFileImportTool.Status(DesktopFileImportTool.Phase.IDLE, 0, 0, 0, 0, 0, List.of());
        when(importSource.status()).thenReturn(importIdle);
        when(importSource.inspect(any())).thenReturn(DesktopFileImportTool.Result.success(
                new DesktopFileImportTool.Preview("token", 1, 1, 1, 0, 0, List.of())));
        when(importSource.start("token", null)).thenReturn(DesktopFileImportTool.Result.success(importIdle));
        when(importSource.pendingCompletion()).thenReturn(Optional.of(new DesktopFileImportTool.Completion("done", 1)));
        imports.register(OWNER, new DesktopFileImportTool.Description(label, label), importSource);

        var keys = new ProgramKeyService(directory.resolve("keys.json"), routes, plugins, media, imports);
        var messages = TestI18nBeans.appMessages();
        var input = new ToolApiRequests(messages, mock(WebI18nService.class));
        var filter = new AuthFilter(mock(SetupService.class), mock(StaticResourceRateLimitService.class), mock(RateLimitService.class),
                mock(AppLocaleResolver.class), messages, mock(ObjectProvider.class), mock(GuestInviteService.class),
                mock(GuiTokenProvider.class), routes, null, null, null, keys);
        var mvc = MockMvcBuilders.standaloneSetup(new MediaToolController(media, mock(DesktopToolHistory.class), input),
                new FileImportToolController(imports, input)).setControllerAdvice(new GlobalExceptionHandler(messages))
                .addFilters(filter).build();

        var core = new HashSet<String>();
        var target = new HashSet<String>();
        for (var grant : keys.permissions()) {
            if (grant.id().startsWith(OWNER.pluginId() + ":media.") || grant.id().startsWith(OWNER.pluginId() + ":file-import.")) {
                target.add(grant.id());
                core.add("core" + grant.id().substring(grant.id().indexOf(':')));
            }
        }
        var both = new HashSet<>(target);
        both.addAll(core);
        var probe = new Probe(paths, mvc, issue(keys, core), issue(keys, target), issue(keys, both));
        for (String tool : List.of("media", "file-import")) {
            String path = "/api/tools/" + tool;
            probe.check(path, tool + ".read", null, false, 200);
            var options = tool.equals("media") ? Map.of("imageFormats", "png", "ugoiraFormats", "gif", "repairThumbnails", false)
                    : Map.of("recordFile", "records.json", "sourceRoot", "files");
            var preview = probe.check(path + "/previews", tool + ".preview", Map.of("identity", ID, "options", options), false, 200);
            var started = probe.check(path + "/tasks", tool + ".execute", Map.of("identity", ID, "token", preview.required("token").asString()), false, 202);
            probe.check(path + "/tasks/current", tool + ".read", null, true, 200);
            probe.check(path + "/tasks/cancel", tool + ".cancel", Map.of("identity", ID, "executionId", started.required("executionId").asString()), false, 200);
        }
        probe.check("/api/tools/media/capabilities", "media.read", ID, false, 200);
        probe.check("/api/tools/file-import/completion/acknowledge", "file-import.acknowledge", Map.of("identity", ID, "completionId", "done"), false, 204);
        verify(mediaSource).start("token");
        verify(importSource).start("token", null);
        verify(mediaSource).cancel();
        verify(importSource).cancel();
        verify(importSource).acknowledgeCompletion("done");
    }

    private static String issue(ProgramKeyService keys, Set<String> grants) throws Exception {
        return keys.create(new ProgramKeyStore.Policy("tool test", grants, false, List.of("127.0.0.1"))).secret();
    }

    private record Probe(JsonNode paths, MockMvc mvc, String core, String provider, String both) {
        JsonNode check(String path, String permission, Map<String, ?> body, boolean query, int success) throws Exception {
            var stages = paths.required(path).required(body == null ? "get" : "post").required("x-program-permissions");
            var condition = stages.requiredAt("/submit/0");
            assertThat(stages.required("route").asString()).isEqualTo("core:" + permission);
            assertThat(condition.required("permissionId").asString()).isEqualTo(permission);
            boolean list = body == null && !query;
            var ownerFrom = condition.required("ownerFrom").asString().split("#", 2);
            assertThat(ownerFrom[0]).isEqualTo(list ? "item" : query ? "query" : "request");

            mvc.perform(request(path, body, query, provider)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.programPermission").value("core:" + permission));
            var routeOnly = mvc.perform(request(path, body, query, core));
            if (list) {
                assertThat(condition.required("effect").asString()).isEqualTo("filter");
                routeOnly.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
            } else {
                assertThat(condition.requiredAt("/reportedIn/status").asInt()).isEqualTo(403);
                assertThat(condition.requiredAt("/reportedIn/pointer").asString()).isEqualTo("/programPermission");
                routeOnly.andExpect(status().isForbidden()).andExpect(jsonPath("$.programPermission").value(OWNER.pluginId() + ":" + permission));
            }
            var response = mvc.perform(request(path, body, query, both)).andExpect(status().is(success)).andReturn().getResponse();
            var json = JsonMapper.shared();
            var result = json.readTree(response.getContentAsByteArray());
            JsonNode ownerContext = list ? result.required(0) : json.valueToTree(query ? ID : body);
            assertThat(ownerContext.requiredAt(ownerFrom[1]).asString()).isEqualTo(OWNER.pluginId());
            return result;
        }

        private MockHttpServletRequestBuilder request(String path, Map<String, ?> body, boolean query, String key) throws Exception {
            var request = body == null ? get(path) : post(path).contentType("application/json").content(JsonMapper.shared().writeValueAsBytes(body));
            if (query) ID.forEach((name, value) -> request.param(name, value.toString()));
            return request.secure(true).header("Authorization", "Bearer " + key);
        }
    }
}
