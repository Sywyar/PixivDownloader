package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("程序权限目录消费当前目标能力")
class ProgramPermissionCatalogTest {
    @TempDir Path directory;

    @Test @DisplayName("未加载但已安装的可管理插件仍可签发目标权限，移除后不能新签发")
    void managementTargetsFollowInstalledInventory() throws Exception {
        var plugins = new PluginRegistry(List.of(new top.sywyar.pixivdownload.plugin.CorePlugin()));
        var target = mock(top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementEntry.class);
        when(target.id()).thenReturn("inactive-provider");
        when(target.toggleable()).thenReturn(true);
        var targets = new java.util.concurrent.atomic.AtomicReference<>(List.of(target));
        var keys = new ProgramKeyService(directory.resolve("keys.json"), new RouteAccessRegistry(plugins), plugins,
                new DesktopMediaToolRegistry(), new DesktopFileImportToolRegistry(),
                new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry(), List::of, targets::get);
        var policy = new ProgramKeyStore.Policy("manage inactive", Set.of("core:plugins.enabled", "inactive-provider:plugins.manage"), true, List.of());
        assertThat(keys.create(policy).key().policy().permissions()).contains("inactive-provider:plugins.manage");
        targets.set(List.of());
        assertThatThrownBy(() -> keys.create(policy)).isInstanceOf(IllegalArgumentException.class);
        assertThat(keys.permissions()).anyMatch(value -> value.id().equals("core:credentials.use"));
    }

    @Test @DisplayName("未知插件发布媒体工具后出现目标权限，撤回后不能继续签发")
    void followsIndependentProviderPublication() throws Exception {
        var core = mock(PixivFeaturePlugin.class);
        when(core.id()).thenReturn("core");
        when(core.routes()).thenReturn(List.of(WebRouteContribution.admin("/api/tools/media/tasks")
                .withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("media.execute", "permission.execute")))));
        var independent = mock(PixivFeaturePlugin.class);
        when(independent.id()).thenReturn("independent-tool");
        when(independent.displayNamespace()).thenReturn("independent");
        when(independent.displayName()).thenReturn("plugin.name");
        var plugins = new PluginRegistry(List.of(core, independent));
        var media = new DesktopMediaToolRegistry();
        var keys = new ProgramKeyService(directory.resolve("keys.json"), new RouteAccessRegistry(plugins), plugins,
                media, new DesktopFileImportToolRegistry());
        var policy = new ProgramKeyStore.Policy("media worker", Set.of("core:media.execute", "independent-tool:media.execute"), true, List.of());
        assertThatThrownBy(() -> keys.create(policy)).isInstanceOf(IllegalArgumentException.class);
        var owner = new ExternalCapabilityOwner("independent-tool", "independent-tool", 2, 9);
        media.register(owner, new DesktopMediaTool.Description(DesktopUiText.key("tool"), "independent", "png", "gif"),
                mock(DesktopMediaTool.Source.class));
        var permission = keys.permissions().stream().filter(value -> value.id().equals("independent-tool:media.execute")).findFirst().orElseThrow();
        assertThat(permission.namespace()).isEqualTo("plugins");
        assertThat(permission.ownerNamespace()).isEqualTo("independent");
        assertThat(keys.create(policy).key().policy().permissions()).contains("independent-tool:media.execute");
        media.unregister(owner);
        assertThat(keys.permissions()).noneMatch(value -> value.id().equals("independent-tool:media.execute"));
        assertThatThrownBy(() -> keys.create(policy)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void httpCatalogListsTheTaskConditionOnlyOnTheSourceGrant() throws Exception {
        var provider = mock(PixivFeaturePlugin.class);
        when(provider.id()).thenReturn("independent-source");
        var policy = mock(PixivFeaturePlugin.class);
        when(policy.id()).thenReturn("policy-only");
        var plugins = new PluginRegistry(List.of(new top.sywyar.pixivdownload.plugin.CorePlugin(), provider, policy));
        var owners = List.of(scheduleOwner(provider.id(), Set.of("illustrations"), Set.of()),
                scheduleOwner(policy.id(), Set.of(), Set.of("account")));
        var keys = new ProgramKeyService(directory.resolve("keys.json"), new RouteAccessRegistry(plugins), plugins,
                new DesktopMediaToolRegistry(), new DesktopFileImportToolRegistry(),
                new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry(), () -> owners, List::of);
        var setup = mock(SetupService.class);
        when(setup.isAdminLoggedIn(any())).thenReturn(true);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ProgramKeysController(keys, setup, null, null, null, null)).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/security/program-keys/permissions"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var catalog = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        for (var grant : List.of("core:authors.refresh", "independent-source:schedule.manage", "policy-only:schedule.manage")) {
            var entry = catalog.valueStream().filter(item -> grant.equals(item.required("id").asString())).findFirst().orElseThrow();
            var metadata = entry.required("endpoints").valueStream().filter(endpoint -> "POST".equals(endpoint.required("method").asString())
                    && "/api/pixiv/user/*/meta/schedule".equals(endpoint.required("path").asString())).toList();
            if (grant.equals("policy-only:schedule.manage")) {
                assertThat(metadata).isEmpty();
                continue;
            }
            assertThat(metadata).hasSize(1);
            if (grant.equals("core:authors.refresh")) assertThat(metadata.getFirst().has("when")).isFalse();
            else assertThat(metadata.getFirst().requiredAt("/when/keyFrom").asString()).isEqualTo("request#/taskId");
        }
    }

    private static top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwnerSnapshot scheduleOwner(
            String id, Set<String> sources, Set<String> policies) {
        var owner = new top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner(id, id, 1);
        return new top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwnerSnapshot(
                owner, 1, "active", sources, Set.of(), Set.of(), policies, Set.of(), List.of());
    }

    @Test @DisplayName("下载类型提供者的授权随提交能力发布和撤回")
    void downloadProviderPermissionsFollowPublication() throws Exception {
        var core = mock(PixivFeaturePlugin.class);
        when(core.id()).thenReturn("core");
        when(core.routes()).thenReturn(List.of(WebRouteContribution.admin("/api/download/tasks")
                .withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("downloads.create", "program.permission.downloads.create")))));
        var provider = mock(PixivFeaturePlugin.class);
        when(provider.id()).thenReturn("independent-download");
        var plugins = new PluginRegistry(List.of(core, provider));
        var downloads = new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry();
        var keys = new ProgramKeyService(directory.resolve("keys.json"), new RouteAccessRegistry(plugins), plugins,
                new DesktopMediaToolRegistry(), new DesktopFileImportToolRegistry(), downloads);
        var policy = new ProgramKeyStore.Policy("download worker", Set.of("core:downloads.create", "independent-download:downloads.create"), true, List.of());
        assertThatThrownBy(() -> keys.create(policy)).isInstanceOf(IllegalArgumentException.class);
        var publication = new ExternalCapabilityOwner("independent-download", "independent-download", 1, 1);
        downloads.register(publication, new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry.Contribution(
                List.of(), List.of(), List.of(), Map.of("example", mock(top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler.class))));
        assertThat(keys.create(policy).key().policy().permissions()).contains("independent-download:downloads.create");
        downloads.withdraw(publication);
        assertThatThrownBy(() -> keys.create(policy)).isInstanceOf(IllegalArgumentException.class);
    }
}
