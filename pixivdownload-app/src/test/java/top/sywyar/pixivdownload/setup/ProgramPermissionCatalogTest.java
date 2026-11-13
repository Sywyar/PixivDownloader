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
