package top.sywyar.pixivdownload.plugin.install;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("插件运行期依赖解析")
class PluginDependencyResolverTest {

    @Test
    @DisplayName("开发模式从受管 generation 描述符恢复插件并校验依赖阶段")
    void developmentRuntimeDescriptorSupportsReactivation() {
        PluginDescriptor dependency = descriptor("dev-dependency", List.of());
        PluginDescriptor target = descriptor("dev-target",
                List.of(new PluginDependencyRef("dev-dependency", "1.0", false)));
        PluginDescriptor staleInstalledTarget = descriptor("dev-target", List.of());
        ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
        PluginRegistry registry = mock(PluginRegistry.class);
        PluginLifecycleService lifecycle = mock(PluginLifecycleService.class);
        when(installer.listInstalled()).thenReturn(List.of(
                new InstalledPlugin(staleInstalledTarget, Path.of("plugins", "dev-target.jar"))));
        when(registry.registeredPlugins()).thenReturn(List.of());
        when(lifecycle.managedPluginIds()).thenReturn(Set.of("dev-dependency", "dev-target"));
        when(lifecycle.descriptor("dev-dependency")).thenReturn(Optional.of(dependency));
        when(lifecycle.descriptor("dev-target")).thenReturn(Optional.of(target));
        when(lifecycle.phase("dev-dependency")).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(lifecycle.phase("dev-target")).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        PluginDependencyResolver resolver = new PluginDependencyResolver(installer, registry, lifecycle);

        var descriptor = resolver.activationDescriptor("dev-target").orElseThrow();

        assertThat(descriptor).isSameAs(target);
        assertThat(descriptor.dependencies()).singleElement()
                .satisfies(ref -> assertThat(ref.pluginId()).isEqualTo("dev-dependency"));
        assertThat(resolver.activationProblems(descriptor)).isEmpty();

        when(lifecycle.phase("dev-dependency")).thenReturn(Optional.of(PluginRuntimePhase.STOPPED));
        assertThat(resolver.activationProblems(descriptor)).singleElement()
                .satisfies(problem -> assertThat(problem.reason())
                        .isEqualTo(PluginDependencyProblem.Reason.UNAVAILABLE));
    }

    private static PluginDescriptor descriptor(String pluginId, List<PluginDependencyRef> dependencies) {
        return new PluginDescriptor(pluginId, pluginId, "1.0.0", PluginApiRequirement.unspecified(),
                dependencies, "example.Plugin", null, pluginId, null, null, null, PluginKind.FEATURE);
    }
}
