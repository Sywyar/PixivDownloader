package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SingleInstanceManager tests")
class SingleInstanceManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY);
    }

    @Test
    @DisplayName("should reject second instance and signal the first one")
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
    @DisplayName("should allow acquiring lock again after close")
    void shouldAllowAcquiringLockAgainAfterClose() throws Exception {
        System.setProperty(RuntimeFiles.INSTANCE_DIR_PROPERTY, tempDir.toString());

        SingleInstanceManager firstManager = SingleInstanceManager.acquire();
        assertThat(firstManager).isNotNull();
        firstManager.close();

        try (SingleInstanceManager secondManager = SingleInstanceManager.acquire()) {
            assertThat(secondManager).isNotNull();
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
