package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.pf4j.DefaultPluginLoader;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.util.FileUtils;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;

/** 插件资源流不进入 JVM 共享 JAR 缓存，避免卸载后仍占用私有产物。 */
public final class PluginArtifactLoader extends DefaultPluginLoader {

    public PluginArtifactLoader(PluginManager pluginManager) {
        super(pluginManager);
    }

    @Override
    public boolean isApplicable(Path pluginPath) {
        return super.isApplicable(pluginPath)
                || (Files.isRegularFile(pluginPath) && FileUtils.isJarFile(pluginPath));
    }

    @Override
    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor descriptor) {
        if (Files.isDirectory(pluginPath)) {
            return super.loadPlugin(pluginPath, descriptor);
        }
        PluginClassLoader loader = createPluginClassLoader(pluginPath, descriptor);
        loader.addFile(pluginPath.toFile());
        return loader;
    }

    @Override
    protected PluginClassLoader createPluginClassLoader(Path pluginPath, PluginDescriptor descriptor) {
        return new PluginClassLoader(pluginManager, descriptor, getClass().getClassLoader()) {
            @Override
            public URL findResource(String name) {
                return uncached(super.findResource(name));
            }

            @Override
            public Enumeration<URL> findResources(String name) throws IOException {
                return Collections.enumeration(Collections.list(super.findResources(name)).stream()
                        .map(PluginArtifactLoader::uncached).toList());
            }
        };
    }

    private static URL uncached(URL resource) {
        if (resource == null || !"jar".equals(resource.getProtocol())) {
            return resource;
        }
        try {
            return new URL(null, resource.toExternalForm(), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    URLConnection connection = new URL(url.toExternalForm()).openConnection();
                    connection.setUseCaches(false);
                    return connection;
                }
            });
        } catch (java.net.MalformedURLException failure) {
            throw new IllegalArgumentException("invalid plugin resource URL: " + resource, failure);
        }
    }
}
