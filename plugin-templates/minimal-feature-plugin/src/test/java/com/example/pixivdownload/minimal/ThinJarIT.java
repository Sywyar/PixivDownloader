package com.example.pixivdownload.minimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinJarIT {

    private static final String PLUGIN_PACKAGE = "com.example.pixivdownload.minimal.";

    @Test
    @DisplayName("verify 产物是根 descriptor、无共享依赖副本的 thin JAR")
    void packagedJarKeepsThinBoundary() throws IOException {
        Path pluginJar = pluginJar();
        assertTrue(Files.isRegularFile(pluginJar), () -> "missing plugin jar: " + pluginJar);

        Set<String> entries = new HashSet<>();
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            jar.stream().forEach(entry -> entries.add(entry.getName()));
        }

        assertTrue(entries.contains("plugin.properties"));
        assertTrue(entries.contains(
                "com/example/pixivdownload/minimal/ExampleMinimalPf4jPlugin.class"));
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("BOOT-INF/")));
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("lib/") || name.endsWith(".jar")));
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("org/pf4j/")));
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("org/springframework/")));
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/")));
    }

    @Test
    @DisplayName("产物主类可按 PF4J 父共享、插件包子加载的等价结构实例化")
    void packagedEntrypointLoadsAcrossTheSharedContractBoundary() throws Exception {
        Path pluginJar = pluginJar();
        URL jarUrl = pluginJar.toUri().toURL();

        try (PluginJarClassLoader loader = new PluginJarClassLoader(
                jarUrl, ThinJarIT.class.getClassLoader())) {
            Class<?> entrypoint = loader.loadClass(
                    "com.example.pixivdownload.minimal.ExampleMinimalPf4jPlugin");
            Object instance = entrypoint.getDeclaredConstructor().newInstance();

            assertInstanceOf(Plugin.class, instance);
            PixivPluginProvider provider = assertInstanceOf(PixivPluginProvider.class, instance);
            PixivFeaturePlugin feature = provider.featurePlugin();
            assertNotNull(feature);
            assertEquals("example-minimal", feature.id());
            assertEquals(List.of("com.example.pixivdownload.minimal.ExampleMinimalConfiguration"),
                    provider.configurationClasses().stream().map(Class::getName).toList());
            assertTrue(provider.configurationClasses().stream()
                    .allMatch(configuration -> configuration.getClassLoader() == loader));
            assertEquals(pluginJar.toUri(), entrypoint.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
    }

    private static Path pluginJar() {
        String buildDirectory = Objects.requireNonNull(
                System.getProperty("example.buildDirectory"), "example.buildDirectory");
        String finalName = Objects.requireNonNull(
                System.getProperty("example.finalName"), "example.finalName");
        return Path.of(buildDirectory, finalName + ".jar").toAbsolutePath().normalize();
    }

    /** Child-first only for plugin-owned classes; PF4J, Spring and plugin-api stay parent-shared. */
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
