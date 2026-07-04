package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.BootstrapProbeFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.BootstrapProbePlugin;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;

/**
 * PF4J 运行时管理封装的诊断边界测试：覆盖「插件目录不存在 / 空目录 / 含坏包」三类，
 * 证明坏包被隔离捕获、不致核心壳启动失败，且各状态可被后续流程据以判断。
 */
@DisplayName("PluginRuntimeManager 插件目录诊断与坏包隔离")
class PluginRuntimeManagerTest {

    private static final String PROBE_ID = "bootstrap-probe";
    private static final String PROBE_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("插件目录不存在：报告 ABSENT、不加载任何插件、不创建目录、不构造 PF4J 实例")
    void absentDirectoryIsReportedAndNotCreated() {
        Path missing = tempDir.resolve("does-not-exist");
        PluginRuntimeManager manager = new PluginRuntimeManager(missing);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(status.directoryPresent()).isFalse();
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        assertThat(status.directory()).isEqualTo(missing.toAbsolutePath().normalize());
        // 缺失目录的常态路径不创建目录、不触碰 PF4J
        assertThat(Files.exists(missing)).isFalse();
        assertThat(manager.pluginManager()).isEmpty();
        assertThat(manager.status()).contains(status);
    }

    @Test
    @DisplayName("插件路径存在但不是目录：报告 ABSENT、不致命")
    void nonDirectoryPathIsReportedAbsent() throws IOException {
        Path file = tempDir.resolve("plugins-as-file");
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(file);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        assertThat(manager.pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("空插件目录：报告 EMPTY、可被后续流程判定为需补齐")
    void emptyDirectoryIsReportedEmpty() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(status.directoryPresent()).isTrue();
        assertThat(status.empty()).isTrue();
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        // 空目录路径不构造 PF4J 实例
        assertThat(manager.pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("目录只含非插件包文件（无 jar/zip）：按候选包口径判为 EMPTY")
    void directoryWithOnlyNonPackagesIsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("README.txt"), "hello", StandardCharsets.UTF_8);
        Files.createDirectory(tempDir.resolve("some-subdir"));
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
    }

    @Test
    @DisplayName("含坏包：报告 POPULATED、坏包被隔离捕获成失败条目、不抛异常、不致核心壳启动失败")
    void brokenPackageIsIsolatedNotFatal() throws IOException {
        // 一个伪装成 .jar 的文本文件——PF4J 解析其描述符时必然失败
        Path broken = tempDir.resolve("broken-plugin.jar");
        Files.writeString(broken, "this is not a valid plugin jar", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        // start() 必须正常返回（不向上抛出），坏包不能让核心壳启动失败
        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.hasFailures()).isTrue();
        assertThat(status.failures()).hasSize(1);
        assertThat(status.failures().get(0).source()).isEqualTo("broken-plugin.jar");
        assertThat(status.failures().get(0).reason()).isNotBlank();
        // POPULATED 路径会创建 PF4J 实例供后续桥接流程使用
        assertThat(manager.pluginManager()).isPresent();
    }

    @Test
    @DisplayName("开发模式：忽略 plugins 目录内容，扫描仓库根模块 target/classes 并加载")
    void developmentModeLoadsCompiledModuleClassesAndIgnoresPluginsDirectory() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Files.writeString(pluginsRoot.resolve("broken-plugin.jar"),
                "this should be ignored in development mode", StandardCharsets.UTF_8);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeProbeSourceDescriptor(moduleRoot);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        writeProbeClassesDirectory(classesDirectory, true);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            Path pf4jPath = manager.pluginManager().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginPath().toAbsolutePath().normalize();
            assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
            assertThat(status.directory()).isEqualTo(repositoryRoot.toAbsolutePath().normalize());
            assertThat(status.loadedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.startedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(manager.artifactPath(PROBE_ID)).contains(classesDirectory.toAbsolutePath().normalize());
            assertThat(pf4jPath).startsWith(repositoryRoot.resolve("target/pixivdownload-plugin-dev-runtime")
                    .toAbsolutePath().normalize());
            assertThat(pf4jPath.getFileName().toString()).isEqualTo(PROBE_ID + "-" + PROBE_VERSION);
            assertThat(pf4jPath.resolve("classes/top/sywyar/pixivdownload/plugin/runtime/bootstrap/"
                    + "BootstrapProbePlugin.class")).exists();
            assertThat(pf4jPath.resolve("lib/private-lib.jar")).exists();
        } finally {
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @DisplayName("开发模式：默认开发根从模块工作目录回溯到仓库根")
    void developmentModeFindsRepositoryRootFromModuleWorkingDirectory() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-from-app");
        Files.createDirectories(repositoryRoot);
        Files.writeString(repositoryRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path pluginsRoot = repositoryRoot.resolve("pixivdownload-app/plugins");
        Files.createDirectories(pluginsRoot);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeProbeSourceDescriptor(moduleRoot);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        writeProbeClassesDirectory(classesDirectory, false);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
            assertThat(status.directory()).isEqualTo(repositoryRoot.toAbsolutePath().normalize());
            assertThat(status.loadedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.startedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.failures()).isEmpty();
        } finally {
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @DisplayName("开发模式：红色输出提示忽略 plugins，并列出未编译的源码插件模块")
    void developmentModePrintsRedBannerAndReportsSourceOnlyModules() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-source-only");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        writeProbeSourceDescriptor(repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe"));
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        PrintStream previousErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
            assertThat(status.failures()).hasSize(1);
            assertThat(status.failures().get(0).source()).isEqualTo(PROBE_ID);
            assertThat(status.failures().get(0).reason()).contains("target/classes/plugin.properties");
        } finally {
            System.setErr(previousErr);
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("\u001B[1;31m")
                .contains("PIXIVDOWNLOAD PLUGIN DEVELOPMENT MODE ENABLED")
                .contains("The plugins directory is ignored")
                .contains("Source plugin modules without target/classes output")
                .contains("pixivdownload-plugin-bootstrap-probe");
    }

    @Test
    @DisplayName("开发模式：按 plugin.dependencies 将依赖模块排在依赖方之前加载")
    void developmentModeOrdersMaterializedPluginsByDependencies() {
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin mail =
                materializedDevelopmentPlugin("mail", List.of(new PluginDependencyRef("notification", "1.0", false)));
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin notification =
                materializedDevelopmentPlugin("notification", List.of());

        List<String> orderedIds = PluginDevelopmentArtifacts.dependencyOrder(List.of(mail, notification)).stream()
                .map(plugin -> plugin.descriptor().id())
                .toList();

        assertThat(orderedIds).containsExactly("notification", "mail");
    }

    @Test
    @DisplayName("重新扫描 POPULATED→EMPTY：清理陈旧 PF4J 实例，pluginManager() 与发现结果均为空")
    void rescanFromPopulatedToEmptyClearsStaleManager() throws IOException {
        Path broken = tempDir.resolve("broken-plugin.jar");
        Files.writeString(broken, "not a valid plugin jar", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus first = manager.start();
        assertThat(first.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(manager.pluginManager()).isPresent();

        // 移除候选包后重新扫描：目录转为空
        Files.delete(broken);
        PluginRuntimeStatus second = manager.start();

        assertThat(second.state()).isEqualTo(PluginDirectoryState.EMPTY);
        // 关键：不得读到上一轮的陈旧 PF4J 实例
        assertThat(manager.pluginManager()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().failures()).isEmpty();
        assertThat(manager.status()).contains(second);
    }

    @Test
    @DisplayName("重新扫描 POPULATED→ABSENT：清理陈旧 PF4J 实例，pluginManager() 与发现结果均为空")
    void rescanFromPopulatedToAbsentClearsStaleManager() throws IOException {
        Path pluginsRoot = tempDir.resolve("plugins");
        Files.createDirectory(pluginsRoot);
        Path broken = pluginsRoot.resolve("broken-plugin.jar");
        Files.writeString(broken, "not a valid plugin jar", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);

        PluginRuntimeStatus first = manager.start();
        assertThat(first.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(manager.pluginManager()).isPresent();

        // 删除整个插件目录后重新扫描：目录转为缺失
        Files.delete(broken);
        Files.delete(pluginsRoot);
        PluginRuntimeStatus second = manager.start();

        assertThat(second.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(manager.pluginManager()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
    }

    @Test
    @DisplayName("未运行 start()（无 PF4J 实例）时发现结果为空")
    void discoverBeforeStartIsEmpty() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().hasFailures()).isFalse();
    }

    @Test
    @DisplayName("未运行 start() 前 status() 为空，运行后缓存结果")
    void statusIsCachedAfterStart() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);
        assertThat(manager.status()).isEmpty();

        PluginRuntimeStatus status = manager.start();

        assertThat(manager.status()).contains(status);
    }

    @Test
    @DisplayName("JAR-with-lib：先验签原始 jar，再物化到 plugins/runtime，并保持 artifactPath 指向原始 jar")
    void jarWithPrivateLibrariesIsMaterializedButExposesOriginalArtifact() throws IOException {
        Path plugins = tempDir.resolve("plugins-with-lib");
        Files.createDirectories(plugins);
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        LoadedPluginPackage loaded = manager.loadPlugin(jar);
        manager.startPlugin(PROBE_ID);

        Path pf4jPath = manager.pluginManager().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(loaded.artifactPath()).isEqualTo(jar.toAbsolutePath().normalize());
        assertThat(manager.artifactPath(PROBE_ID)).contains(jar.toAbsolutePath().normalize());
        assertThat(pf4jPath).startsWith(plugins.resolve(PluginRuntimeLayout.RUNTIME_DIR).toAbsolutePath().normalize());
        assertThat(pf4jPath).isNotEqualTo(jar.toAbsolutePath().normalize());
        assertThat(pf4jPath.resolve("plugin.properties")).exists();
        assertThat(pf4jPath.resolve("classes/top/sywyar/pixivdownload/plugin/runtime/bootstrap/BootstrapProbePlugin.class"))
                .exists();
        assertThat(pf4jPath.resolve("lib/private-lib.jar")).exists();
        assertThat(plugins.resolve(PROBE_ID + "-" + PROBE_VERSION)).doesNotExist();
        manager.shutdown();
    }

    @Test
    @DisplayName("JAR-with-lib：同一 sha 的不同 artifact 路径复用同一个 runtime cache")
    void jarWithPrivateLibrariesReusesRuntimeCacheAcrossArtifactPathsWithSameSha() throws IOException {
        Path plugins = tempDir.resolve("plugins-reusable-cache");
        Path fullOffline = plugins.resolve("full-offline");
        Path portable = plugins.resolve("portable");
        Files.createDirectories(fullOffline);
        Files.createDirectories(portable);
        Path firstJar = fullOffline.resolve("pixivdownload-plugin-gui-theme-1.0.0.jar");
        Path secondJar = portable.resolve("pixivdownload-plugin-gui-theme.jar");
        writeProbeJar(firstJar, true);
        Files.write(secondJar, Files.readAllBytes(firstJar));
        writeLocalProvenance(plugins, firstJar);
        writeLocalProvenance(plugins, secondJar);
        String sha256 = PluginPackageIntegrity.sha256Hex(firstJar);
        assertThat(PluginPackageIntegrity.sha256Hex(secondJar)).isEqualTo(sha256);
        Path expectedCache = plugins.resolve(PluginRuntimeLayout.RUNTIME_DIR)
                .resolve(PROBE_ID + "-" + PROBE_VERSION + "-" + sha256)
                .toAbsolutePath().normalize();

        PluginRuntimeManager firstManager = new PluginRuntimeManager(plugins);
        LoadedPluginPackage firstLoaded = firstManager.loadPlugin(firstJar);
        firstManager.startPlugin(PROBE_ID);
        Path firstPf4jPath = firstManager.pluginManager().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(firstLoaded.artifactPath()).isEqualTo(firstJar.toAbsolutePath().normalize());
        assertThat(firstPf4jPath).isEqualTo(expectedCache);
        firstManager.shutdown();

        PluginRuntimeManager secondManager = new PluginRuntimeManager(plugins);
        LoadedPluginPackage secondLoaded = secondManager.loadPlugin(secondJar);
        secondManager.startPlugin(PROBE_ID);
        Path secondPf4jPath = secondManager.pluginManager().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();

        assertThat(secondLoaded.artifactPath()).isEqualTo(secondJar.toAbsolutePath().normalize());
        assertThat(secondManager.artifactPath(PROBE_ID)).contains(secondJar.toAbsolutePath().normalize());
        assertThat(secondPf4jPath).isEqualTo(firstPf4jPath);
        assertThat(readCacheMarker(secondPf4jPath).getProperty("artifact.path"))
                .isEqualTo(secondJar.toAbsolutePath().normalize().toString());
        secondManager.shutdown();
    }

    @Test
    @DisplayName("ZIP 兼容：不让 PF4J 直接加载根 zip，而是物化到 plugins/runtime 目录后加载一次")
    void zipPackageIsMaterializedBeforePf4jLoad() throws IOException {
        Path plugins = tempDir.resolve("plugins-zip");
        Files.createDirectories(plugins);
        Path zip = plugins.resolve("bootstrap-probe-1.0.0.zip");
        writeProbeExplodedZip(zip);
        writeLocalProvenance(plugins, zip);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        LoadedPluginPackage loaded = manager.loadPlugin(zip);

        Path pf4jPath = manager.pluginManager().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(loaded.artifactPath()).isEqualTo(zip.toAbsolutePath().normalize());
        assertThat(pf4jPath).startsWith(plugins.resolve(PluginRuntimeLayout.RUNTIME_DIR).toAbsolutePath().normalize());
        assertThat(pf4jPath).isDirectory();
        assertThat(pf4jPath).isNotEqualTo(zip.toAbsolutePath().normalize());
        assertThat(plugins.resolve(PROBE_ID + "-" + PROBE_VERSION)).doesNotExist();
        manager.shutdown();
    }

    @Test
    @DisplayName("构造参数为 null 时立即抛出")
    void nullRootRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PluginRuntimeManager(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void writeProbeJar(Path jar, boolean privateLib) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDescriptor(zos);
            addClassEntry(zos, BootstrapProbePlugin.class, "");
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class, "");
            if (privateLib) {
                zos.putNextEntry(new ZipEntry("lib/private-lib.jar"));
                zos.write(nestedJarBytes());
                zos.closeEntry();
            }
        }
    }

    private static void writeProbeExplodedZip(Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDescriptor(zos);
            addClassEntry(zos, BootstrapProbePlugin.class, "classes/");
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class, "classes/");
        }
    }

    private static void writeProbeClassesDirectory(Path classesDirectory, boolean privateLib) throws IOException {
        Files.createDirectories(classesDirectory);
        String props = "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n";
        Files.writeString(classesDirectory.resolve("plugin.properties"), props, StandardCharsets.UTF_8);
        copyClassFile(classesDirectory, BootstrapProbePlugin.class);
        copyClassFile(classesDirectory, BootstrapProbeFeaturePlugin.class);
        if (privateLib) {
            Path lib = classesDirectory.resolve("lib/private-lib.jar");
            Files.createDirectories(lib.getParent());
            Files.write(lib, nestedJarBytes());
        }
    }

    private static void copyClassFile(Path classesDirectory, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path target = classesDirectory.resolve(entry);
        Files.createDirectories(target.getParent());
        try (InputStream in = type.getResourceAsStream("/" + entry)) {
            assertThat(in).as("class resource must be compiled: " + type.getName()).isNotNull();
            Files.copy(in, target);
        }
    }

    private PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materializedDevelopmentPlugin(
            String pluginId, List<PluginDependencyRef> dependencies) {
        Path moduleRoot = tempDir.resolve("pixivdownload-plugin-" + pluginId);
        PluginDescriptor descriptor = new PluginDescriptor(pluginId, pluginId, PROBE_VERSION,
                PluginApiRequirement.of(1, 0), dependencies, "com.example." + pluginId.replace("-", "") + ".Plugin",
                null, pluginId, null, null, null, PluginKind.FEATURE);
        return new PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin(
                moduleRoot, moduleRoot.resolve("target/classes"),
                tempDir.resolve("cache").resolve(pluginId), descriptor);
    }

    private static void addDescriptor(ZipOutputStream zos) throws IOException {
        String props = "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n";
        zos.putNextEntry(new ZipEntry("plugin.properties"));
        zos.write(props.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void addClassEntry(ZipOutputStream zos, Class<?> type, String prefix) throws IOException {
        String entry = prefix + type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(in).as("class resource must be compiled: " + type.getName()).isNotNull();
            bytes = in.readAllBytes();
        }
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static byte[] nestedJarBytes() throws IOException {
        try (var out = new java.io.ByteArrayOutputStream();
             var zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("private/Marker.txt"));
            zos.write("private-lib".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            return out.toByteArray();
        }
    }

    private static void writeLocalProvenance(Path pluginsDir, Path artifact) throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                PROBE_ID, PROBE_VERSION, null, null, null, null, Instant.now(), Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(artifact, PluginPackageOrigin.localUpload(), result);
    }

    private static Properties readCacheMarker(Path runtimeCache) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(runtimeCache.resolve(".pixiv-plugin-runtime-cache"))) {
            properties.load(in);
        }
        return properties;
    }

    private static void writeProbeSourceDescriptor(Path moduleRoot) throws IOException {
        Path sourceResources = moduleRoot.resolve("src/main/resources");
        Files.createDirectories(sourceResources);
        Files.writeString(sourceResources.resolve("plugin.properties"),
                "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                        + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n",
                StandardCharsets.UTF_8);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
