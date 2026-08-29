package top.sywyar.pixivdownload.plugin.runtime.pf4j;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.DefaultPluginManager;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginFactory;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;

import java.lang.reflect.Constructor;
import java.nio.file.Path;

/**
 * 只允许宿主通过公开无参构造器实例化插件的 PF4J manager。
 * 插件对象不会取得 {@link PluginWrapper}，wrapper 本身也不公开宿主的物理 manager。
 */
public final class HostControlledPluginManager extends DefaultPluginManager {

    public HostControlledPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    @Override
    protected PluginFactory createPluginFactory() {
        return new NoWrapperPluginFactory();
    }

    @Override
    protected PluginWrapper createPluginWrapper(
            PluginDescriptor descriptor,
            Path pluginPath,
            ClassLoader pluginClassLoader
    ) {
        PluginWrapper wrapper = new ManagerHiddenPluginWrapper(this, descriptor, pluginPath, pluginClassLoader);
        wrapper.setPluginFactory(getPluginFactory());
        return wrapper;
    }

    private static final class NoWrapperPluginFactory extends DefaultPluginFactory {

        @Override
        protected Plugin createInstance(Class<?> pluginClass, PluginWrapper wrapper) {
            try {
                pluginClass.getDeclaredConstructor(PluginWrapper.class);
                throw new PluginRuntimeException(
                        "plugin class '{}' must not declare a PluginWrapper constructor",
                        pluginClass.getName());
            } catch (NoSuchMethodException ignored) {
                // 继续要求公开无参构造器。
            }

            try {
                Constructor<?> constructor = pluginClass.getConstructor();
                return (Plugin) constructor.newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new PluginRuntimeException(
                        failure,
                        "plugin class '{}' must expose a public no-argument constructor",
                        pluginClass.getName());
            }
        }
    }

    private static final class ManagerHiddenPluginWrapper extends PluginWrapper {

        private ManagerHiddenPluginWrapper(
                PluginManager manager,
                PluginDescriptor descriptor,
                Path pluginPath,
                ClassLoader pluginClassLoader
        ) {
            super(manager, descriptor, pluginPath, pluginClassLoader);
        }

        @Override
        public PluginManager getPluginManager() {
            throw new UnsupportedOperationException("physical plugin manager is host-only");
        }
    }
}
