package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.pf4j.DefaultPluginLoader;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.jar.JarFile;

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
        return new ResourceClassLoader(pluginManager, descriptor, getClass().getClassLoader());
    }

    private static final class ResourceClassLoader extends PluginClassLoader {
        private final Set<JarFile> resourceJars = Collections.newSetFromMap(new WeakHashMap<>());
        private boolean closed;

        private ResourceClassLoader(PluginManager manager, PluginDescriptor descriptor, ClassLoader parent) {
            super(manager, descriptor, parent);
        }

        @Override public URL findResource(String name) { return uncached(super.findResource(name)); }

        @Override
        public Enumeration<URL> findResources(String name) throws IOException {
            return Collections.enumeration(Collections.list(super.findResources(name)).stream()
                    .map(this::uncached).toList());
        }

        private URL uncached(URL resource) {
            if (resource == null || !"jar".equals(resource.getProtocol())) return resource;
            try {
                return new URL(null, resource.toExternalForm(), new URLStreamHandler() {
                    @Override protected void parseURL(URL url, String spec, int start, int limit) {
                        try {
                            URL base = url.getFile() == null || url.getFile().isEmpty()
                                    ? null : new URL(url.toExternalForm());
                            URL parsed = new URL(base, spec);
                            setURL(url, parsed.getProtocol(), parsed.getHost(), parsed.getPort(), parsed.getAuthority(),
                                    parsed.getUserInfo(), parsed.getPath(), parsed.getQuery(), parsed.getRef());
                        } catch (java.net.MalformedURLException failure) {
                            throw new IllegalArgumentException(failure);
                        }
                    }

                    @Override protected URLConnection openConnection(URL url) throws IOException {
                        return new ResourceConnection(url);
                    }
                });
            } catch (java.net.MalformedURLException failure) {
                throw new IllegalArgumentException("invalid plugin resource URL: " + resource, failure);
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (resourceJars) {
                if (closed) return;
                closed = true;
                IOException failure = null;
                try { super.close(); } catch (IOException error) { failure = error; }
                for (JarFile jar : resourceJars) {
                    try { jar.close(); } catch (IOException error) {
                        if (failure == null) failure = error; else failure.addSuppressed(error);
                    }
                }
                resourceJars.clear();
                if (failure != null) throw failure;
            }
        }

        private final class ResourceConnection extends JarURLConnection {
            private final JarURLConnection delegate;

            private ResourceConnection(URL url) throws IOException {
                super(url);
                delegate = (JarURLConnection) new URL(url.toExternalForm()).openConnection();
                setUseCaches(false);
            }

            @Override
            public void connect() throws IOException {
                synchronized (resourceJars) {
                    if (closed) throw new IOException("PLUGIN_RESOURCE_LOADER_CLOSED");
                    if (connected) return;
                    delegate.setUseCaches(getUseCaches());
                    delegate.setConnectTimeout(getConnectTimeout());
                    delegate.setReadTimeout(getReadTimeout());
                    delegate.setAllowUserInteraction(getAllowUserInteraction());
                    delegate.setIfModifiedSince(getIfModifiedSince());
                    getRequestProperties().forEach((key, values) ->
                            values.forEach(value -> delegate.addRequestProperty(key, value)));
                    delegate.connect();
                    // 弱引用沿用 URLClassLoader 的所有权方式，不延长读取方已释放对象的生命周期。
                    if (!getUseCaches()) resourceJars.add(delegate.getJarFile());
                    connected = true;
                }
            }

            @Override public JarFile getJarFile() throws IOException { connect(); return delegate.getJarFile(); }
            @Override public InputStream getInputStream() throws IOException {
                synchronized (resourceJars) {
                    connect();
                    return delegate.getInputStream();
                }
            }
            @Override public java.security.Permission getPermission() throws IOException { return delegate.getPermission(); }

            @Override public int getContentLength() {
                long length = getContentLengthLong();
                return length > Integer.MAX_VALUE ? -1 : (int) length;
            }

            @Override public long getContentLengthLong() {
                if (getEntryName() == null) return getHeaderFieldLong("content-length", -1);
                try { connect(); return delegate.getContentLengthLong(); } catch (IOException failure) { return -1; }
            }

            @Override public String getContentType() {
                try { connect(); return delegate.getContentType(); } catch (IOException failure) { return null; }
            }

            @Override public String getHeaderField(String name) {
                synchronized (resourceJars) {
                    if (closed) return null;
                    try {
                        URLConnection metadata = getJarFileURL().openConnection();
                        metadata.setUseCaches(false);
                        // 文件 URL 的头字段读取也会打开流，不能把这份句柄留给 GC。
                        try (InputStream ignored = metadata.getInputStream()) {
                            return metadata.getHeaderField(name);
                        }
                    } catch (IOException failure) { return null; }
                }
            }

            @Override public Object getContent() throws IOException {
                return getEntryName() == null ? getJarFile() : super.getContent();
            }
        }
    }
}
