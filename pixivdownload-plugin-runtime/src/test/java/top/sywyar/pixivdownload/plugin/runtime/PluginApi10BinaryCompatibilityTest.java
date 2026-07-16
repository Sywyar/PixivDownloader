package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.slf4j.Logger;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
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

/** 使用冻结的未发布 1.0 草案 ABI 编译真实 thin PF4J JAR，防止当前 API 的默认方法破坏早期插件加载。 */
@DisplayName("Plugin API 外置插件二进制兼容")
class PluginApi10BinaryCompatibilityTest {

    private static final String PLUGIN_ID = "api10-binary-fixture";
    private static final String PRE_PREPARE_PLUGIN_ID = "api10-pre-prepare-binary-fixture";
    private static final String PRE_ROUTE_PLUGIN_ID = "api10-pre-route-binary-fixture";
    private static final String PLUGIN_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("按冻结 1.0 草案 ABI 编译的 thin JAR 可由 1.0 核心真实加载并调用新增默认方法")
    void frozenApi10JarLoadsAgainstCurrentApi() throws Exception {
        assertThat(PluginApiVersion.VERSION).isEqualTo("1.0.0");
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
    @DisplayName("按尚无 prepare 的 1.0 草案 ABI 编译的来源执行器可调用当前默认 prepare")
    void prePrepareApi10SourceExecutorUsesCurrentDefaultPrepare() throws Exception {
        assertThat(PluginApiVersion.VERSION).isEqualTo("1.0.0");
        Path oldApiJar = compilePrePrepareApi10();
        Path plugins = tempDir.resolve("plugins-api10-pre-prepare");
        Files.createDirectories(plugins);
        Path fixtureJar = compilePrePrepareFixtureJar(
                oldApiJar, plugins.resolve(PRE_PREPARE_PLUGIN_ID + ".jar"));
        writeLocalProvenance(plugins, fixtureJar, PRE_PREPARE_PLUGIN_ID, PLUGIN_VERSION);

        try (ZipFile zip = new ZipFile(fixtureJar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName))
                    .noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
        }

        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        try {
            PluginRuntimeStatus status = manager.start();
            PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();

            assertThat(status.loadedPluginIds()).containsExactly(PRE_PREPARE_PLUGIN_ID);
            assertThat(status.startedPluginIds()).containsExactly(PRE_PREPARE_PLUGIN_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(discovery.failures()).isEmpty();
            assertThat(discovery.discovered()).singleElement()
                    .satisfies(item -> assertThat(item.plugin().id()).isEqualTo(PRE_PREPARE_PLUGIN_ID));

            ScheduledSourceExecutor executor = (ScheduledSourceExecutor) discovery.discovered().get(0).plugin();
            assertThat(executor.sourceType()).isEqualTo("api10-pre-prepare-fixture-source");
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

    @Test
    @DisplayName("按尚无来源路由的 1.0 草案 ABI 编译的来源执行器可调用旧九参数执行计划构造器")
    void preRouteApi10SourceExecutorUsesCompatibleNineArgumentPlanConstructor() throws Exception {
        assertThat(PluginApiVersion.VERSION).isEqualTo("1.0.0");
        Path oldApiJar = compilePreRouteApi10();
        Path plugins = tempDir.resolve("plugins-api10-pre-route");
        Files.createDirectories(plugins);
        Path fixtureJar = compilePreRouteFixtureJar(
                oldApiJar, plugins.resolve(PRE_ROUTE_PLUGIN_ID + ".jar"));
        writeLocalProvenance(plugins, fixtureJar, PRE_ROUTE_PLUGIN_ID, PLUGIN_VERSION);

        try (ZipFile zip = new ZipFile(fixtureJar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName))
                    .noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
        }

        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        try {
            PluginRuntimeStatus status = manager.start();
            PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();

            assertThat(status.loadedPluginIds()).containsExactly(PRE_ROUTE_PLUGIN_ID);
            assertThat(status.startedPluginIds()).containsExactly(PRE_ROUTE_PLUGIN_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(discovery.failures()).isEmpty();
            assertThat(discovery.discovered()).singleElement()
                    .satisfies(item -> assertThat(item.plugin().id()).isEqualTo(PRE_ROUTE_PLUGIN_ID));

            ScheduledSourceExecutor executor = (ScheduledSourceExecutor) discovery.discovered().get(0).plugin();
            ScheduledExecutionPlan plan = executor.plan(new ScheduledTaskDefinition(
                    23L,
                    executor.sourceType(),
                    "fixture.definition",
                    1,
                    "{\"value\":1}",
                    new ScheduledTaskPresentation("任务", null, Map.of("kind", "fixture"))));

            assertThat(plan.requiredWorkTypes()).containsExactly("api10-pre-route-work");
            assertThat(plan.sourceDefaultRoute()).isEqualTo(ScheduledNetworkRoute.inherit());
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

    private Path compilePrePrepareApi10() throws IOException {
        Path sources = tempDir.resolve("api10-pre-prepare-src");
        Path classes = tempDir.resolve("api10-pre-prepare-classes");
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
        Path jar = tempDir.resolve("pixivdownload-plugin-api-1.0-pre-prepare-fixture.jar");
        zipClasses(classes, jar, null);
        return jar;
    }

    private Path compilePreRouteApi10() throws IOException {
        Path sources = tempDir.resolve("api10-pre-route-src");
        Path classes = tempDir.resolve("api10-pre-route-classes");
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
                "top/sywyar/pixivdownload/plugin/api/schedule/credential/ScheduledCredentialRequirement.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.credential;
                public enum ScheduledCredentialRequirement { NONE, OPTIONAL, REQUIRED }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/guard/ScheduledGuardPoint.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.guard;
                public enum ScheduledGuardPoint { RUN_START, WORK_BATCH, RUN_END, RUN_FAILURE }
                """);
        writeSource(sources,
                "top/sywyar/pixivdownload/plugin/api/schedule/guard/ScheduledGuardBinding.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.guard;
                import java.util.Set;
                public record ScheduledGuardBinding(
                        String guardId, Set<ScheduledGuardPoint> points, int workBatchSize) {}
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
                import java.util.List;
                import java.util.Set;
                import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
                import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
                public record ScheduledExecutionPlan(
                        Set<String> requiredWorkTypes,
                        String credentialPolicyId,
                        ScheduledCredentialRequirement credentialRequirement,
                        boolean anonymousFallbackAllowed,
                        List<ScheduledGuardBinding> guards,
                        String checkpointSchema,
                        int checkpointVersion,
                        int maxInFlight,
                        long politeDelayMillis) {}
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
                "top/sywyar/pixivdownload/plugin/api/schedule/source/ScheduledTaskDraft.java", """
                package top.sywyar.pixivdownload.plugin.api.schedule.source;
                public record ScheduledTaskDraft(
                        long taskId,
                        String sourceType,
                        String definitionSchema,
                        int definitionVersion,
                        String definitionJson,
                        ScheduledTaskPresentation presentation) {
                    public ScheduledTaskDefinition toDefinition() {
                        return new ScheduledTaskDefinition(
                                taskId, sourceType, definitionSchema, definitionVersion,
                                definitionJson, presentation);
                    }
                }
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
                    default ScheduledTaskDefinition prepare(ScheduledTaskDraft draft)
                            throws ScheduledExecutionException {
                        return draft.toDefinition();
                    }
                    ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                            throws ScheduledExecutionException;
                    ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                            throws ScheduledExecutionException;
                }
                """);
        compile(classes, null, javaSources(sources));
        Path jar = tempDir.resolve("pixivdownload-plugin-api-1.0-pre-route-fixture.jar");
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

    private Path compilePrePrepareFixtureJar(Path oldApiJar, Path jar) throws IOException, URISyntaxException {
        Path sources = tempDir.resolve("api10-pre-prepare-fixture-src");
        Path classes = tempDir.resolve("api10-pre-prepare-fixture-classes");
        writeSource(sources, "fixture/preprepare/PrePrepareFeature.java", """
                package fixture.preprepare;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
                public final class PrePrepareFeature implements PixivFeaturePlugin, ScheduledSourceExecutor {
                    public String id() { return "api10-pre-prepare-binary-fixture"; }
                    public String displayName() { return "plugin.name"; }
                    public String description() { return "plugin.summary"; }
                    public PluginKind kind() { return PluginKind.FEATURE; }
                    public String sourceType() { return "api10-pre-prepare-fixture-source"; }
                    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                            throws ScheduledExecutionException { return null; }
                    public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                            throws ScheduledExecutionException { return null; }
                }
                """);
        writeSource(sources, "fixture/preprepare/PrePreparePlugin.java", """
                package fixture.preprepare;
                import java.util.List;
                import org.pf4j.Plugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
                public final class PrePreparePlugin extends Plugin implements PixivPluginProvider {
                    public List<PixivFeaturePlugin> featurePlugins() {
                        return List.of(new PrePrepareFeature());
                    }
                }
                """);
        String classpath = String.join(File.pathSeparator,
                oldApiJar.toString(), codeSource(Plugin.class).toString(), codeSource(Logger.class).toString());
        compile(classes, classpath, javaSources(sources));
        String descriptor = "plugin.id=" + PRE_PREPARE_PLUGIN_ID + "\n"
                + "plugin.version=" + PLUGIN_VERSION + "\n"
                + "plugin.requires=1.0\n"
                + "plugin.class=fixture.preprepare.PrePreparePlugin\n"
                + "plugin.provider=test\n"
                + "plugin.description=api 1.0 pre-prepare binary fixture\n";
        zipClasses(classes, jar, descriptor);
        return jar;
    }

    private Path compilePreRouteFixtureJar(Path oldApiJar, Path jar) throws IOException, URISyntaxException {
        Path sources = tempDir.resolve("api10-pre-route-fixture-src");
        Path classes = tempDir.resolve("api10-pre-route-fixture-classes");
        writeSource(sources, "fixture/preroute/PreRouteFeature.java", """
                package fixture.preroute;
                import java.util.List;
                import java.util.Set;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
                import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
                import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
                import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
                public final class PreRouteFeature implements PixivFeaturePlugin, ScheduledSourceExecutor {
                    public String id() { return "api10-pre-route-binary-fixture"; }
                    public String displayName() { return "plugin.name"; }
                    public String description() { return "plugin.summary"; }
                    public PluginKind kind() { return PluginKind.FEATURE; }
                    public String sourceType() { return "api10-pre-route-fixture-source"; }
                    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                            throws ScheduledExecutionException {
                        return new ScheduledExecutionPlan(
                                Set.of("api10-pre-route-work"),
                                null,
                                ScheduledCredentialRequirement.NONE,
                                false,
                                List.of(),
                                null,
                                0,
                                1,
                                0L);
                    }
                    public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
                            throws ScheduledExecutionException { return null; }
                }
                """);
        writeSource(sources, "fixture/preroute/PreRoutePlugin.java", """
                package fixture.preroute;
                import java.util.List;
                import org.pf4j.Plugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
                public final class PreRoutePlugin extends Plugin implements PixivPluginProvider {
                    public List<PixivFeaturePlugin> featurePlugins() {
                        return List.of(new PreRouteFeature());
                    }
                }
                """);
        String classpath = String.join(File.pathSeparator,
                oldApiJar.toString(), codeSource(Plugin.class).toString(), codeSource(Logger.class).toString());
        compile(classes, classpath, javaSources(sources));
        String descriptor = "plugin.id=" + PRE_ROUTE_PLUGIN_ID + "\n"
                + "plugin.version=" + PLUGIN_VERSION + "\n"
                + "plugin.requires=1.0\n"
                + "plugin.class=fixture.preroute.PreRoutePlugin\n"
                + "plugin.provider=test\n"
                + "plugin.description=api 1.0 pre-route binary fixture\n";
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
