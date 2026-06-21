package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
}
