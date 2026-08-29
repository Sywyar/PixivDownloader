package top.sywyar.pixivdownload.plugin.runtime.pf4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginFactory;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PF4J 宿主控制面")
class HostControlledPluginManagerTest {

    @TempDir
    Path pluginsRoot;

    @Test
    @DisplayName("只允许公开无参插件构造器")
    void onlyAllowsPublicNoArgumentConstructor() {
        HostControlledPluginManager manager = new HostControlledPluginManager(pluginsRoot);
        PluginFactory factory = manager.createPluginFactory();

        assertThat(factory.create(wrapperFor(NoArgumentPlugin.class))).isInstanceOf(NoArgumentPlugin.class);
        assertThatThrownBy(() -> factory.create(wrapperFor(WrapperConstructorPlugin.class)))
                .isInstanceOf(PluginRuntimeException.class)
                .hasMessageContaining("must not declare a PluginWrapper constructor");
    }

    @Test
    @DisplayName("插件 wrapper 不暴露物理 manager")
    void wrapperDoesNotExposePhysicalManager() {
        HostControlledPluginManager manager = new HostControlledPluginManager(pluginsRoot);
        PluginWrapper wrapper = manager.createPluginWrapper(
                mock(PluginDescriptor.class), pluginsRoot.resolve("plugin.jar"), getClass().getClassLoader());

        assertThatThrownBy(wrapper::getPluginManager)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("physical plugin manager is host-only");
    }

    private PluginWrapper wrapperFor(Class<? extends Plugin> pluginClass) {
        PluginDescriptor descriptor = mock(PluginDescriptor.class);
        when(descriptor.getPluginClass()).thenReturn(pluginClass.getName());
        PluginWrapper wrapper = mock(PluginWrapper.class);
        when(wrapper.getDescriptor()).thenReturn(descriptor);
        when(wrapper.getPluginClassLoader()).thenReturn(pluginClass.getClassLoader());
        return wrapper;
    }

    public static final class NoArgumentPlugin extends Plugin {
        public NoArgumentPlugin() {
        }
    }

    public static final class WrapperConstructorPlugin extends Plugin {
        public WrapperConstructorPlugin(PluginWrapper wrapper) {
        }
    }
}
