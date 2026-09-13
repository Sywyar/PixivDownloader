package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClassLoader;

import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginArtifactLoaderTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("独立 JAR 与目录私有库的资源可读取且关闭后立即释放文件")
    void resourceReadsReleaseJarWithoutChangingGlobalCacheDefaults(boolean directory) throws Exception {
        Path jar = tempDir.resolve(directory ? "插件 布局/lib/private.jar" : "插件 文件.jar");
        Files.createDirectories(jar.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"probe/first.txt", "probe/second.txt"}) {
                output.putNextEntry(new ZipEntry(name));
                output.write(name.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        Path artifact = directory ? jar.getParent().getParent() : jar;
        var loaderFactory = new PluginArtifactLoader(new DefaultPluginManager(tempDir.resolve("plugins")));
        var descriptor = new DefaultPluginDescriptor("resource-probe", "", "example.Probe", "1.0.0", "", "", "");
        boolean globalCacheDefault = URLConnection.getDefaultUseCaches("jar");
        try (var loader = (PluginClassLoader) loaderFactory.loadPlugin(artifact, descriptor)) {
            URL resource = loader.getResource("probe/first.txt");
            assertThat(resource).isNotNull();
            assertThat(resource.openConnection()).isInstanceOf(JarURLConnection.class);
            assertThat(resource.openConnection().getUseCaches()).isFalse();
            try (var input = resource.openStream()) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("probe/first.txt");
            }
            try (var input = new URL(resource, "second.txt").openStream()) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("probe/second.txt");
            }
            var resources = Collections.list(loader.getResources("probe/first.txt"));
            assertThat(resources).hasSize(1);
            for (URL entry : resources) {
                assertThat(entry.openConnection().getUseCaches()).isFalse();
                try (var input = entry.openStream()) {
                    assertThat(input.read()).isEqualTo('p');
                }
            }
        }
        Files.delete(jar);
        assertThat(jar).doesNotExist();
        assertThat(URLConnection.getDefaultUseCaches("jar")).isEqualTo(globalCacheDefault);
    }
}
