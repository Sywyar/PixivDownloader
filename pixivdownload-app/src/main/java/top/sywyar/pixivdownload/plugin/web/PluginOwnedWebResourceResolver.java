package top.sywyar.pixivdownload.plugin.web;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Objects;
import java.util.jar.JarFile;

/**
 * 把插件静态资源声明解析为 owner 自身代码来源下的直接资源根。解析不经过可向父级或依赖插件委派的
 * {@link ClassLoader#getResource(String)}，因此同 classpath 路径的宿主 / 依赖资源不能冒充当前插件包资源。
 */
@Component
public final class PluginOwnedWebResourceResolver {

    public Resource resolveLocation(PluginRegistry.RegisteredPlugin owner,
                                    StaticResourceContribution contribution) {
        Objects.requireNonNull(owner, "registered plugin owner");
        Objects.requireNonNull(contribution, "static resource contribution");
        Class<?> pluginClass = AopProxyUtils.ultimateTargetClass(owner.plugin());
        if (pluginClass.getClassLoader() != owner.classLoader()) {
            throw new IllegalStateException("plugin implementation is not defined by its registered classloader: "
                    + owner.id());
        }
        CodeSource codeSource = pluginClass.getProtectionDomain() == null
                ? null : pluginClass.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("plugin implementation has no verifiable code source: " + owner.id());
        }

        String relativeLocation = normalizedClasspathDirectory(
                contribution.classpathLocation(), owner.id());
        try {
            URL root = ownerRoot(codeSource.getLocation(), pluginClass);
            URL resolved = new URL(root, relativeLocation);
            if (!isUnderRoot(root, resolved)) {
                throw new IllegalStateException("static resource location escapes its owner code source");
            }
            return new UrlResource(resolved);
        } catch (Exception failure) {
            throw new IllegalStateException("failed to resolve owner-only static resource root for plugin: "
                    + owner.id(), failure);
        }
    }

    private static URL ownerRoot(URL codeSourceLocation, Class<?> pluginClass) throws Exception {
        if ("file".equalsIgnoreCase(codeSourceLocation.getProtocol())) {
            Path path = Path.of(codeSourceLocation.toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                String classEntry = pluginClass.getName().replace('.', '/') + ".class";
                String entryPrefix;
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getJarEntry(classEntry) != null) {
                        entryPrefix = "";
                    } else if (jar.getJarEntry("BOOT-INF/classes/" + classEntry) != null) {
                        entryPrefix = "BOOT-INF/classes/";
                    } else {
                        throw new IllegalStateException(
                                "plugin implementation is absent from its code source archive: " + classEntry);
                    }
                }
                return URI.create("jar:" + path.toUri().toASCIIString() + "!/" + entryPrefix).toURL();
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException("plugin code source is neither a directory nor an archive: " + path);
            }
            Path classFile = path.resolve(pluginClass.getName().replace('.', '/') + ".class").normalize();
            if (!classFile.startsWith(path) || !Files.isRegularFile(classFile)) {
                throw new IllegalStateException("plugin implementation is absent from its code source directory: "
                        + classFile);
            }
        }
        String external = codeSourceLocation.toExternalForm();
        if (!external.endsWith("/")) {
            throw new IllegalStateException("plugin code source is not a resource root: " + external);
        }
        return codeSourceLocation;
    }

    private static String normalizedClasspathDirectory(String value, String pluginId) {
        if (value == null || !value.startsWith("classpath:/") || !value.endsWith("/")) {
            throw new IllegalStateException("invalid static resource classpath location: " + value
                    + " (plugin: " + pluginId + ")");
        }
        String relative = value.substring("classpath:/".length());
        for (int index = 0; index < relative.length(); index++) {
            char current = relative.charAt(index);
            if (!isSafePathCharacter(current)) {
                throw new IllegalStateException("static resource classpath location contains a forbidden character: "
                        + value + " (plugin: " + pluginId + ")");
            }
        }
        URI normalized = URI.create("/" + relative).normalize();
        if (!normalized.getRawPath().equals("/" + relative)
                || relative.startsWith("/") || relative.contains("//")) {
            throw new IllegalStateException("static resource classpath location must be normalized: " + value
                    + " (plugin: " + pluginId + ")");
        }
        return relative;
    }

    private static boolean isSafePathCharacter(char value) {
        return value == '/' || value == '-' || value == '_' || value == '.' || value == '~'
                || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static boolean isUnderRoot(URL root, URL resolved) {
        String rootValue = root.toExternalForm();
        return rootValue.endsWith("/") && resolved.toExternalForm().startsWith(rootValue);
    }
}
