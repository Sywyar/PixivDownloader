package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF4J 运行时管理封装的诊断边界测试：覆盖「插件目录不存在 / 空目录 / 含坏包」三类，
 * 证明坏包被隔离捕获、不致核心壳启动失败，且各状态可被后续流程据以判断。
 */
@DisplayName("PluginRuntimeManager 插件目录诊断与坏包隔离")
class PluginRuntimeManagerTest {

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
    @DisplayName("未运行 start() 前 status() 为空，运行后缓存结果")
    void statusIsCachedAfterStart() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);
        assertThat(manager.status()).isEmpty();

        PluginRuntimeStatus status = manager.start();

        assertThat(manager.status()).contains(status);
    }

    @Test
    @DisplayName("构造参数为 null 时立即抛出")
    void nullRootRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PluginRuntimeManager(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
