package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.slf4j.Logger;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用冻结的历史 ABI 编译真实 thin PF4J JAR，防止当前 API 的默认方法破坏旧插件加载。 */
@DisplayName("Plugin API 外置插件二进制兼容")
class PluginApi10BinaryCompatibilityTest {

    private static final String PLUGIN_ID = "api10-binary-fixture";
    private static final String API11_PLUGIN_ID = "api11-binary-fixture";
    private static final String PLUGIN_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("按冻结 1.0 ABI 编译的 thin JAR 可由 1.2 核心真实加载并调用新增默认方法")
    void frozenApi10JarLoadsAgainstCurrentApi() throws Exception {
        assertThat(PluginApiVersion.VERSION).isEqualTo("1.2.0");
        Path oldApiJar = compileFrozenApi10();
        Path plugins = tempDir.resolve("plugins");
        Files.createDirectories(plugins);
        Path fixtureJar = compileFixtureJar(oldApiJar, plugins.resolve(PLUGIN_ID + ".jar"));
        writeLocalProvenance(plugins, fixtureJar);

        try (ZipFile zip = new ZipFile(fixtureJar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName))
                    .noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
        }

        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        try {
            PluginRuntimeStatus status = manager.start();
            PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();

            assertThat(status.loadedPluginIds()).containsExactly(PLUGIN_ID);
            assertThat(status.startedPluginIds()).containsExactly(PLUGIN_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(discovery.failures()).isEmpty();
            assertThat(discovery.discovered()).singleElement().satisfies(item -> {
                assertThat(item.plugin().id()).isEqualTo(PLUGIN_ID);
                assertThat(item.plugin().scheduledSources()).singleElement().satisfies(source -> {
                    assertThat(source.type()).isEqualTo("legacy-fixture-source");
                    assertThat(source.legacyTypeNames()).containsExactly("LEGACY_FIXTURE_SOURCE");
                });
                assertThat(item.plugin().scheduledSourceDescriptors()).isEmpty();
            });
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("按冻结 1.1 ABI 编译的来源执行器由 1.2 核心加载并调用默认 prepare")
    void frozenApi11SourceExecutorUsesCurrentDefaultPrepare() throws Exception {
        assertThat(PluginApiVersion.VERSION).isEqualTo("1.2.0");
        Path oldApiJar = compileFrozenApi11();
        Path plugins = tempDir.resolve("plugins-api11");
        Files.createDirectories(plugins);
        Path fixtureJar = compileApi11FixtureJar(
                oldApiJar, plugins.resolve(API11_PLUGIN_ID + ".jar"));
        writeLocalProvenance(plugins, fixtureJar, API11_PLUGIN_ID, PLUGIN_VERSION);

        try (ZipFile zip = new ZipFile(fixtureJar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName))
                    .noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
        }

        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        try {
            PluginRuntimeStatus status = manager.start();
            PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();

            assertThat(status.loadedPluginIds()).containsExactly(API11_PLUGIN_ID);
            assertThat(status.startedPluginIds()).containsExactly(API11_PLUGIN_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(discovery.failures()).isEmpty();
            assertThat(discovery.discovered()).singleElement()
                    .satisfies(item -> assertThat(item.plugin().id()).isEqualTo(API11_PLUGIN_ID));

            ScheduledSourceExecutor executor = (ScheduledSourceExecutor) discovery.discovered().get(0).plugin();
            assertThat(executor.sourceType()).isEqualTo("api11-fixture-source");
            assertThat(executor.getClass().getDeclaredMethods())
                    .noneMatch(method -> method.getName().equals("prepare"));
            assertThat(ScheduledSourceExecutor.class
                    .getMethod("prepare", ScheduledTaskDraft.class)
                    .isDefault()).isTrue();

            ScheduledTaskDraft draft = new ScheduledTaskDraft(
                    17L,
                    executor.sourceType(),
                    "fixture.definition",
                    1,
                    "{\"value\":1}",
                    new ScheduledTaskPresentation("任务", null, Map.of("kind", "fixture")));
            ScheduledTaskDefinition prepared = executor.prepare(draft);

            assertThat(prepared).isEqualTo(draft.toDefinition());
        } finally {
            manager.shutdown();
        }
    }

    private Path compileFrozenApi10() throws IOException {
        Path sources = tempDir.resolve("api10-src");
        Path classes = tempDir.resolve("api10-classes");
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PluginKind.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                public enum PluginKind { CORE, FEATURE }
                """);
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PixivFeaturePlugin.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                import java.util.List;
                import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
                public interface PixivFeaturePlugin {
                    String id();
                    String displayName();
                    String description();
                    PluginKind kind();
                    default List<ScheduledSourceProvider> scheduledSources() { return List.of(); }
                }
                """);
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/schedule/ScheduledSourceProvider.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule;
                import java.util.Set;
                public interface ScheduledSourceProvider {
                    String type();
                    default Set<String> legacyTypeNames() { return Set.of(); }
                }
                """);
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PixivPluginProvider.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                import java.util.List;
                public interface PixivPluginProvider {
                    List<PixivFeaturePlugin> featurePlugins();
                }
                """);
        compile(classes, null, javaSources(sources));
        Path jar = tempDir.resolve("pixivdownload-plugin-api-1.0-fixture.jar");
        zipClasses(classes, jar, null);
        return jar;
    }

    private Path compileFrozenApi11() throws IOException {
        Path sources = tempDir.resolve("api11-src");
        Path classes = tempDir.resolve("api11-classes");
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PluginKind.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                public enum PluginKind { CORE, FEATURE }
                """);
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PixivFeaturePlugin.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                public interface PixivFeaturePlugin {
                    String id();
                    String displayName();
                    String description();
                    PluginKind kind();
                }
                """);
        writeSource(sources, "top/sywyar/pixivdownload/plugin/api/plugin/PixivPluginProvider.java", """
                package top.sywyar.pixivdownload.plugin.api.plugin;
                import java.util.List;
                public interface PixivPluginProvider {
                    List<PixivFeaturePlugin> featurePlugins();
                }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/execution/ScheduledExecutionException.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.execution;
                public class ScheduledExecutionException extends Exception {
                    public ScheduledExecutionException(String message) { super(message); }
                }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/execution/ScheduledExecutionPlan.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.execution;
                public final class ScheduledExecutionPlan {
                    private ScheduledExecutionPlan() {}
                }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledTaskPresentation.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                import java.util.Map;
                public record ScheduledTaskPresentation(
                        String title, String summary, Map<String, String> attributes) {}
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledTaskDefinition.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                public record ScheduledTaskDefinition(
                        long taskId,
                        String sourceType,
                        String definitionSchema,
                        int definitionVersion,
                        String definitionJson,
                        ScheduledTaskPresentation presentation) {}
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledPendingReplayPolicy.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                public enum ScheduledPendingReplayPolicy { ALWAYS, REDISCOVERED_ONLY }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledDiscoveryResult.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                public final class ScheduledDiscoveryResult {
                    private ScheduledDiscoveryResult() {}
                }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledSourceContext.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                public interface ScheduledSourceContext {}
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledSourceExecutor.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
                public interface ScheduledSourceExecutor {
                    String sourceType();
                    default ScheduledPendingReplayPolicy pendingReplayPolicy() {
                        return ScheduledPendingReplayPolicy.ALWAYS;
                    }
                    ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                            throws ScheduledExecutionException;
                    ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                            throws ScheduledExecutionException;
                }
                """);
        compile(classes, null, javaSources(sources));
        Path jar = tempDir.resolve("pixivdownload-plugin-api-1.1-fixture.jar");
        zipClasses(classes, jar, null);
        return jar;
    }

    private Path compileFixtureJar(Path oldApiJar, Path jar) throws IOException, URISyntaxException {
        Path sources = tempDir.resolve("fixture-src");
        Path classes = tempDir.resolve("fixture-classes");
        writeSource(sources, "fixture/api10/Api10Feature.java", """
                package fixture.api10;
                import java.util.List;
                import java.util.Set;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
                import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
                public final class Api10Feature implements PixivFeaturePlugin {
                    public String id() { return "api10-binary-fixture"; }
                    public String displayName() { return "plugin.name"; }
                    public String description() { return "plugin.summary"; }
                    public PluginKind kind() { return PluginKind.FEATURE; }
                    public List<ScheduledSourceProvider> scheduledSources() {
                        return List.of(new ScheduledSourceProvider() {
                            public String type() { return "legacy-fixture-source"; }
                            public Set<String> legacyTypeNames() { return Set.of("LEGACY_FIXTURE_SOURCE"); }
                        });
                    }
                }
                """);
        writeSource(sources, "fixture/api10/Api10Plugin.java", """
                package fixture.api10;
                import java.util.List;
                import org.pf4j.Plugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
                public final class Api10Plugin extends Plugin implements PixivPluginProvider {
                    public List<PixivFeaturePlugin> featurePlugins() {
                        return List.of(new Api10Feature());
                    }
                }
                """);
        String classpath = String.join(File.pathSeparator,
                oldApiJar.toString(), codeSource(Plugin.class).toString(), codeSource(Logger.class).toString());
        compile(classes, classpath, javaSources(sources));
        String descriptor = "plugin.id=" + PLUGIN_ID + "\n"
                + "plugin.version=" + PLUGIN_VERSION + "\n"
                + "plugin.requires=1.0\n"
                + "plugin.class=fixture.api10.Api10Plugin\n"
                + "plugin.provider=test\n"
                + "plugin.description=api 1.0 binary fixture\n";
        zipClasses(classes, jar, descriptor);
        return jar;
    }

    private Path compileApi11FixtureJar(Path oldApiJar, Path jar) throws IOException, URISyntaxException {
        Path sources = tempDir.resolve("api11-fixture-src");
        Path classes = tempDir.resolve("api11-fixture-classes");
        writeSource(sources, "fixture/api11/Api11Feature.java", """
                package fixture.api11;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
                public final class Api11Feature implements PixivFeaturePlugin, ScheduledSourceExecutor {
                    public String id() { return "api11-binary-fixture"; }
                    public String displayName() { return "plugin.name"; }
                    public String description() { return "plugin.summary"; }
                    public PluginKind kind() { return PluginKind.FEATURE; }
                    public String sourceType() { return "api11-fixture-source"; }
                    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                            throws ScheduledExecutionException { return null; }
                    public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                            throws ScheduledExecutionException { return null; }
                }
                """);
        writeSource(sources, "fixture/api11/Api11Plugin.java", """
                package fixture.api11;
                import java.util.List;
                import org.pf4j.Plugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
                public final class Api11Plugin extends Plugin implements PixivPluginProvider {
                    public List<PixivFeaturePlugin> featurePlugins() {
                        return List.of(new Api11Feature());
                    }
                }
                """);
        String classpath = String.join(File.pathSeparator,
                oldApiJar.toString(), codeSource(Plugin.class).toString(), codeSource(Logger.class).toString());
        compile(classes, classpath, javaSources(sources));
        String descriptor = "plugin.id=" + API11_PLUGIN_ID + "\n"
                + "plugin.version=" + PLUGIN_VERSION + "\n"
                + "plugin.requires=1.1\n"
                + "plugin.class=fixture.api11.Api11Plugin\n"
                + "plugin.provider=test\n"
                + "plugin.description=api 1.1 binary fixture\n";
        zipClasses(classes, jar, descriptor);
        return jar;
    }

    private static void compile(Path output, String classpath, List<Path> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("测试必须运行在 JDK 上").isNotNull();
        Files.createDirectories(output);
        List<String> args = new ArrayList<>(List.of(
                "-encoding", "UTF-8", "--release", "17", "-d", output.toString()));
        if (classpath != null) {
            args.add("-classpath");
            args.add(classpath);
        }
        sources.forEach(path -> args.add(path.toString()));
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exit = compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertThat(exit)
                .withFailMessage(() -> diagnostics.toString(StandardCharsets.UTF_8))
                .isZero();
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void writeSource(Path root, String relative, String source) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void zipClasses(Path classes, Path jar, String descriptor) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entry = classes.relativize(path).toString().replace('\\', '/');
                out.putNextEntry(new ZipEntry(entry));
                Files.copy(path, out);
                out.closeEntry();
            }
            if (descriptor != null) {
                out.putNextEntry(new ZipEntry("plugin.properties"));
                out.write(descriptor.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static void writeLocalProvenance(Path plugins, Path artifact) throws IOException {
        writeLocalProvenance(plugins, artifact, PLUGIN_ID, PLUGIN_VERSION);
    }

    private static void writeLocalProvenance(
            Path plugins, Path artifact, String pluginId, String pluginVersion) throws IOException {
        VerificationResult result = new VerificationResult(
                VerificationStatus.UNSIGNED_ALLOWED,
                pluginId,
                pluginVersion,
                null,
                null,
                null,
                null,
                Instant.now(),
                Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact),
                "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(plugins).write(artifact, PluginPackageOrigin.localUpload(), result);
    }
}
