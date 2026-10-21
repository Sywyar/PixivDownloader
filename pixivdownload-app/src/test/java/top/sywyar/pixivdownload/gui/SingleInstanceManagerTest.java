package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("单实例锁与激活监听")
class SingleInstanceManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY);
    }

    @Test
    @DisplayName("拒绝第二个实例并唤醒已运行实例")
    void shouldRejectSecondInstanceAndSignalTheFirstOne() throws Exception {
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, tempDir.toString());
        CountDownLatch activationLatch = new CountDownLatch(1);

        try (SingleInstanceManager manager = SingleInstanceManager.acquire()) {
            assertThat(manager).isNotNull();
            manager.setActivationHandler(activationLatch::countDown);

            SingleInstanceManager secondManager = SingleInstanceManager.acquire();
            assertThat(secondManager).isNull();
            assertThat(SingleInstanceManager.signalExistingInstance()).isTrue();
            assertThat(activationLatch.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("关闭后可以重新取得实例锁")
    void shouldAllowAcquiringLockAgainAfterClose() throws Exception {
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, tempDir.toString());

        SingleInstanceManager firstManager = SingleInstanceManager.acquire();
        assertThat(firstManager).isNotNull();
        firstManager.close();

        try (SingleInstanceManager secondManager = SingleInstanceManager.acquire()) {
            assertThat(secondManager).isNotNull();
        }
    }

    @Test
    @DisplayName("激活文件发布失败时释放监听端口和实例锁，允许修复后重试")
    void failedActivationPublicationReleasesResources() throws Exception {
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, tempDir.toString());
        Path activationFile = Files.createDirectory(tempDir.resolve("single-instance.txt"));
        Path blocker = Files.writeString(activationFile.resolve("blocker"), "keep", StandardCharsets.UTF_8);

        assertThatThrownBy(SingleInstanceManager::acquire).isInstanceOf(IOException.class);

        // 真实失败保留的暂存内容记录本次绑定的端口，不依赖管理器私有字段。
        int port = Integer.parseInt(Files.readAllLines(tempDir.resolve("single-instance.txt.tmp"),
                StandardCharsets.UTF_8).get(0));
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        }
        assertThat(Files.readString(blocker, StandardCharsets.UTF_8)).isEqualTo("keep");
        Files.delete(blocker);
        Files.delete(activationFile);
        try (SingleInstanceManager retry = SingleInstanceManager.acquire()) {
            assertThat(retry).isNotNull();
            assertThat(SingleInstanceManager.signalExistingInstance()).isTrue();
        }
    }

    @Test
    @DisplayName("取得文件锁失败时关闭通道并保留原异常")
    void failedLockAcquisitionClosesChannel() throws Exception {
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, tempDir.toString());
        FileChannel channel = mock(FileChannel.class);
        IOException failure = new IOException("injected lock failure");
        when(channel.tryLock()).thenThrow(failure);
        try (var channels = mockStatic(FileChannel.class)) {
            channels.when(() -> FileChannel.open(tempDir.resolve("single-instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)).thenReturn(channel);
            assertThatThrownBy(SingleInstanceManager::acquire).isSameAs(failure);
            verify(channel).close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "true,  --no-gui,          true,  78, 0",
            "true,  --startup,         true,  78, 0",
            "false, --no-gui,          true,   0, 1",
            "false, --startup,         true,   0, 0",
            "false, --debug,           true,   0, 1",
            "true,  --setup,           true,  75, 0",
            "true,  --change-password, true,  75, 0",
            "true,  --reset-password,  true,  75, 0",
            "false, --setup,           true,  75, 0",
            "true,  --no-gui,          false, 78, 0",
            "false, --no-gui,          false, 78, 0"
    })
    @DisplayName("开发启动不占锁或唤醒已有实例，普通启动与管理命令保留排他行为")
    void launcherHonorsDevelopmentMode(boolean development, String argument, boolean existing,
                                      int expectedExit, int expectedActivations) throws Exception {
        Path instanceDir = tempDir.resolve("instance");
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, instanceDir.toString());
        AtomicInteger activations = new AtomicInteger();

        try (SingleInstanceManager manager = existing ? SingleInstanceManager.acquire() : null) {
            Path activationFile = instanceDir.resolve("single-instance.txt");
            byte[] originalActivation = existing ? Files.readAllBytes(activationFile) : null;
            if (existing) {
                assertThat(manager).isNotNull();
                manager.setActivationHandler(activations::incrementAndGet);
            }

            // 未初始化的无头启动以 78 退出，证明新进程已越过单实例检查。
            assertLauncherExit(development, argument, instanceDir, expectedExit);

            assertThat(activations.get()).isEqualTo(expectedActivations);
            if (existing) {
                assertThat(Files.readAllBytes(activationFile)).isEqualTo(originalActivation);
                try (SingleInstanceManager second = SingleInstanceManager.acquire()) {
                    assertThat(second).isNull();
                }
            } else {
                assertThat(Files.exists(instanceDir)).isEqualTo(!development);
            }
        }
    }

    private void assertLauncherExit(boolean development, String argument, Path instanceDir,
                                    int expectedExit) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        Path output = tempDir.resolve("console.txt");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(List.of(
                java.toString(), "-Djava.awt.headless=true", "-Duser.language=en", "-Duser.country=US",
                "-D" + PluginDevelopmentArtifacts.ENABLED_PROPERTY + "=" + development,
                "-D" + RuntimeFiles.INSTANCE_DIR_PROPERTY + "=" + instanceDir,
                "-D" + RuntimeFiles.CONFIG_DIR_PROPERTY + "=" + tempDir.resolve("config"),
                "-D" + RuntimeFiles.STATE_DIR_PROPERTY + "=" + tempDir.resolve("state"),
                "-cp", classPath, GuiLauncher.class.getName(), argument))
                .directory(tempDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("启动子进程应及时退出").isTrue();
            assertThat(process.exitValue()).as(Files.readString(output, StandardCharsets.UTF_8))
                    .isEqualTo(expectedExit);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
