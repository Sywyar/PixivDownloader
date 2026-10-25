package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClassLoader;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginArtifactLoaderTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("独立 JAR 与目录私有库的资源可读取且关闭后立即释放文件")
    void resourceReadsReleaseJarWithoutChangingGlobalCacheDefaults(boolean directory) throws Exception {
        Path jar = createResources(directory);
        Path artifact = directory ? jar.getParent().getParent() : jar;
        var loaderFactory = new PluginArtifactLoader(new DefaultPluginManager(tempDir.resolve("plugins")));
        var descriptor = new DefaultPluginDescriptor("resource-probe", "", "example.Probe", "1.0.0", "", "", "");
        boolean globalCacheDefault = URLConnection.getDefaultUseCaches("jar");
        try (var loader = (PluginClassLoader) loaderFactory.loadPlugin(artifact, descriptor)) {
            URL resource = loader.getResource("probe/first.txt");
            assertThat(resource).isNotNull();
            var connection = (JarURLConnection) resource.openConnection();
            assertThat(connection.getUseCaches()).isFalse();
            assertThat(connection.getEntryName()).isEqualTo("probe/first.txt");
            assertThat(Path.of(connection.getJarFileURL().toURI())).isEqualTo(jar);
            assertThat(connection.getContentLengthLong()).isEqualTo("probe/first.txt".length());
            assertThat(connection.getContentType()).isEqualTo("text/plain");
            assertThat(connection.getLastModified()).isEqualTo(Files.getLastModifiedTime(jar).toMillis() / 1000 * 1000);
            assertThat(connection.getJarEntry().getName()).isEqualTo("probe/first.txt");
            assertThat(connection.getPermission()).isNotNull();
            var archive = (JarURLConnection) new URL(resource, "/").openConnection();
            assertThat(archive.getContentLengthLong()).isEqualTo(Files.size(jar));
            assertThat(archive.getContent()).isInstanceOf(java.util.jar.JarFile.class);
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
            assertThatThrownBy(() -> new URL(resource, "missing.txt").openStream())
                    .isInstanceOf(java.io.FileNotFoundException.class);
        }
        Files.delete(jar);
        assertThat(jar).doesNotExist();
        assertThat(URLConnection.getDefaultUseCaches("jar")).isEqualTo(globalCacheDefault);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("关闭插件加载器释放资源 URL 打开的流且不影响另一加载器")
    void closeReleasesResourceUrlStreamsWithoutClosingAnotherOwner(boolean directory) throws Exception {
        Path jar = createResources(directory);
        Path artifact = directory ? jar.getParent().getParent() : jar;
        var factory = new PluginArtifactLoader(new DefaultPluginManager(tempDir.resolve("plugins")));
        var descriptor = new DefaultPluginDescriptor("resource-probe", "", "example.Probe", "1.0.0", "", "", "");
        try (var first = (PluginClassLoader) factory.loadPlugin(artifact, descriptor);
             var second = (PluginClassLoader) factory.loadPlugin(artifact, descriptor)) {
            URL resource = first.getResource("probe.txt");
            var deferred = resource.openConnection();
            try (var abandoned = resource.openStream();
                 var other = second.getResource("probe.txt").openStream()) {
                assertThat(abandoned.read()).isEqualTo('v');
                first.close();
                assertThatThrownBy(abandoned::read).isInstanceOf(IOException.class);
                assertThatThrownBy(deferred::getInputStream).isInstanceOf(IOException.class);
                assertThatThrownBy(resource::openStream).isInstanceOf(IOException.class);
                assertThat(deferred.getHeaderField("content-length")).isNull();
                assertThat(new String(other.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("version=value");
            }
        }
        Files.delete(jar);
        assertThat(jar).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("调用方显式共享的 JAR 缓存不被单个加载器关闭")
    void closeDoesNotInvalidateExplicitlySharedJarCache(boolean directory) throws Exception {
        Path jar = createResources(directory);
        Path artifact = directory ? jar.getParent().getParent() : jar;
        var factory = new PluginArtifactLoader(new DefaultPluginManager(tempDir.resolve("plugins")));
        var descriptor = new DefaultPluginDescriptor("resource-probe", "", "example.Probe", "1.0.0", "", "", "");
        JarFile shared = null;
        try (var first = (PluginClassLoader) factory.loadPlugin(artifact, descriptor);
             var second = (PluginClassLoader) factory.loadPlugin(artifact, descriptor)) {
            var left = (JarURLConnection) first.getResource("probe.txt").openConnection();
            var right = (JarURLConnection) second.getResource("probe.txt").openConnection();
            left.setUseCaches(true);
            right.setUseCaches(true);
            shared = left.getJarFile();
            assertThat(right.getJarFile()).isSameAs(shared);
            first.close();
            try (var input = right.getInputStream()) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("version=value");
            }
        } finally {
            if (shared != null) shared.close();
        }
        Files.delete(jar);
        assertThat(jar).doesNotExist();
    }

    private Path createResources(boolean directory) throws IOException {
        Path jar = tempDir.resolve(directory ? "插件 布局/lib/private.jar" : "插件 文件.jar");
        Files.createDirectories(jar.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : Map.of("probe/first.txt", "probe/first.txt",
                    "probe/second.txt", "probe/second.txt", "probe.txt", "version=value").entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
