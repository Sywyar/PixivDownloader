package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.schema.PluginSchemaLifecycle;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("插件 schema 接入可见性顺序")
class PluginSchemaAdoptionVisibilityTest {

    @Test
    @DisplayName("schema 成功前 registry、managed 与 LOADED 阶段均不可见")
    void schemaReadinessPrecedesAllAdoptionVisibility() {
        String pluginId = "schema-visibility";
        PluginRegistry registry = new PluginRegistry(List.of());
        PluginLifecycleState state = new PluginLifecycleState();
        AtomicReference<PluginLifecycleService> serviceRef = new AtomicReference<>();
        AtomicBoolean observedBeforePublication = new AtomicBoolean();
        PluginSchemaLifecycle schemaLifecycle = registered -> {
            assertThat(registry.allRegisteredPlugins()).isEmpty();
            assertThat(serviceRef.get().managedPluginIds()).isEmpty();
            assertThat(state.phase(pluginId)).isEmpty();
            observedBeforePublication.set(true);
        };
        PluginLifecycleService service = service(registry, state, schemaLifecycle);
        serviceRef.set(service);

        service.adoptLoadedPackage(loadedPackage(pluginId));

        assertThat(observedBeforePublication).isTrue();
        assertThat(registry.allRegisteredPlugins())
                .extracting(PluginRegistry.RegisteredPlugin::id)
                .containsExactly(pluginId);
        assertThat(service.managedPluginIds()).containsExactly(pluginId);
        assertThat(state.phase(pluginId)).contains(PluginRuntimePhase.LOADED);
    }

    @Test
    @DisplayName("schema 失败时不留下任何 adopt 可见状态")
    void schemaFailureLeavesAdoptionInvisible() {
        String pluginId = "schema-failure";
        PluginRegistry registry = new PluginRegistry(List.of());
        PluginLifecycleState state = new PluginLifecycleState();
        PluginLifecycleService service = service(registry, state, registered -> {
            throw new IllegalStateException("schema-failed");
        });

        assertThatThrownBy(() -> service.adoptLoadedPackage(loadedPackage(pluginId)))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("failed to adopt new generation")
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("schema-failed");

        assertThat(registry.allRegisteredPlugins()).isEmpty();
        assertThat(service.managedPluginIds()).isEmpty();
        assertThat(state.phase(pluginId)).isEmpty();
    }

    private PluginLifecycleService service(
            PluginRegistry registry,
            PluginLifecycleState state,
            PluginSchemaLifecycle schemaLifecycle) {
        return new PluginLifecycleService(
                mock(ApplicationContext.class),
                mock(PluginRuntimeManager.class),
                new PluginApplicationContextFactory(),
                mock(PluginControllerRegistrar.class),
                mock(PluginWebContributionRegistrar.class),
                mock(PluginScheduleContributionRegistrar.class),
                mock(PluginRuntimeTaskQuiescer.class),
                mock(PluginCapabilityContributionRegistrar.class),
                registry,
                state,
                schemaLifecycle);
    }

    private LoadedPluginPackage loadedPackage(String pluginId) {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return pluginId;
            }

            @Override
            public String displayName() {
                return pluginId;
            }

            @Override
            public String description() {
                return pluginId;
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }
        };
        PluginInstallation installation = mock(PluginInstallation.class);
        when(installation.registrable()).thenReturn(true);
        when(installation.id()).thenReturn(pluginId);
        when(installation.plugin()).thenReturn(plugin);
        when(installation.classLoader()).thenReturn(getClass().getClassLoader());
        PluginInventory inventory = mock(PluginInventory.class);
        when(inventory.installations()).thenReturn(List.of(installation));
        LoadedPluginPackage loaded = mock(LoadedPluginPackage.class);
        when(loaded.packageId()).thenReturn(pluginId);
        when(loaded.generation()).thenReturn(7L);
        when(loaded.inventory()).thenReturn(inventory);
        when(loaded.contextModules()).thenReturn(List.of());
        return loaded;
    }
}
