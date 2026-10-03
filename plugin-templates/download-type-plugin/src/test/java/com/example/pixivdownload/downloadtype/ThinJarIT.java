package com.example.pixivdownload.downloadtype;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinJarIT {

    private static final String PLUGIN_PACKAGE =
            "com.example.pixivdownload.downloadtype.";

    @Test
    @DisplayName("打包产物是根描述符完整且不复制宿主依赖的 thin JAR")
    void packagedArtifactIsThin() throws IOException {
        Path artifact = pluginJar();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            Set<String> entries = new HashSet<>();
            jar.stream().forEach(entry -> entries.add(entry.getName()));

            assertTrue(entries.contains("plugin.properties"));
            assertTrue(entries.contains(
                    "com/example/pixivdownload/downloadtype/ExampleDownloadPf4jPlugin.class"));
            assertTrue(entries.contains(
                    "static/example-download/example-download-type.js"));
            assertTrue(entries.contains("static/example-download-gallery.html"));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("BOOT-INF/")));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("lib/")));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("org/pf4j/")));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("org/springframework/")));
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("com/fasterxml/jackson/")));
            assertFalse(entries.stream().anyMatch(name ->
                    name.startsWith("top/sywyar/pixivdownload/plugin/api/")));
            assertFalse(entries.stream().anyMatch(name ->
                    name.startsWith("top/sywyar/pixivdownload/core/")
                            || name.startsWith("top/sywyar/pixivdownload/plugin/runtime/")));
        }
    }

    @Test
    @DisplayName("打包入口可在共享契约父加载器与插件包子加载器边界实例化")
    void packagedEntrypointLoadsAcrossSharedContractBoundary() throws Exception {
        Path artifact = pluginJar();
        try (PluginJarClassLoader loader = new PluginJarClassLoader(
                artifact.toUri().toURL(), getClass().getClassLoader())) {
            Class<?> entrypoint = loader.loadClass(
                    "com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin");
            Object instance = entrypoint.getDeclaredConstructor().newInstance();

            assertInstanceOf(Plugin.class, instance);
            PixivPluginProvider provider = assertInstanceOf(PixivPluginProvider.class, instance);
            assertEquals("example-download", provider.featurePlugin().id());
            assertEquals(List.of(
                            "com.example.pixivdownload.downloadtype.ExampleDownloadConfiguration"),
                    provider.configurationClasses().stream().map(Class::getName).toList());
            assertTrue(provider.configurationClasses().stream()
                    .allMatch(configuration -> configuration.getClassLoader() == loader));
            assertEquals(artifact.toUri(),
                    entrypoint.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
    }

    private static Path pluginJar() {
        return Path.of(System.getProperty("example.buildDirectory"),
                        System.getProperty("example.finalName") + ".jar")
                .toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("打包控制器保留 Spring 请求参数绑定所需的参数名")
    void packagedControllerRetainsRequestParameterNames() throws Exception {
        try (PluginJarClassLoader loader = new PluginJarClassLoader(
                pluginJar().toUri().toURL(), getClass().getClassLoader())) {
            Class<?> controller = loader.loadClass(
                    "com.example.pixivdownload.downloadtype.web.ExampleDownloadController");
            int implicitNames = 0;
            for (var method : controller.getDeclaredMethods()) {
                for (var parameter : method.getParameters()) {
                    var query = parameter.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
                    var path = parameter.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
                    if (query != null && query.name().isEmpty() && query.value().isEmpty()
                            || path != null && path.name().isEmpty() && path.value().isEmpty()) {
                        assertTrue(parameter.isNamePresent(), () -> "HTTP parameter name missing: " + method);
                        implicitNames++;
                    }
                }
            }
            assertTrue(implicitNames > 0);
        }
    }

    /** Child-first only for template-owned classes; PF4J, Spring and plugin-api remain parent-shared. */
    private static final class PluginJarClassLoader extends URLClassLoader {

        private PluginJarClassLoader(URL pluginJar, ClassLoader parent) {
            super(new URL[]{pluginJar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith(PLUGIN_PACKAGE)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
