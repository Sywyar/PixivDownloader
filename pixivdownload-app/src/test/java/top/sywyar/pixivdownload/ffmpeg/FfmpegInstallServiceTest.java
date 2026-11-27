package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FfmpegInstallServiceTest {
    @TempDir Path directory;

    @Test void sharedInstallerRejectsReentrantInstallationAndReleasesAdmissionAfterFailure() {
        org.junit.jupiter.api.Assumptions.assumeTrue(FfmpegInstaller.supportsManagedDownload());
        try (var locator = mockStatic(FfmpegLocator.class, CALLS_REAL_METHODS)) {
            locator.when(FfmpegLocator::managedToolsDir).thenReturn(directory.resolve("tools"));
            locator.when(FfmpegLocator::managedLicenseDir).thenReturn(directory.resolve("licenses"));
            assertThrows(java.util.concurrent.CancellationException.class, () -> FfmpegInstaller.installManaged(null, (stage, current, total) -> {
                assertTrue(FfmpegInstaller.isInstalling());
                assertThrows(FfmpegInstaller.BusyException.class, () -> FfmpegInstaller.installManaged(null, null));
                throw new java.util.concurrent.CancellationException();
            }));
            assertFalse(FfmpegInstaller.isInstalling());
        }
    }

    @Test void completionProgressDoesNotReleaseAdmissionBeforeInstallerCleanupReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = new FfmpegInstallService((proxy, progress) -> {
            progress.onProgress(FfmpegInstaller.ProgressStage.COMPLETED, 1, 1);
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        try {
            var accepted = service.start(FfmpegInstaller.ProxySettings.disabled());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(accepted.executionId(), service.snapshot().executionId());
            assertEquals("RUNNING", service.snapshot().state());
            assertThrows(FfmpegInstaller.BusyException.class, () -> service.start(FfmpegInstaller.ProxySettings.disabled()));
            release.countDown();
            // 等待真实 worker 退出，再核验终态，不按进度中的 COMPLETED 推断完成。
            service.close();
            assertNotEquals("RUNNING", service.snapshot().state());
            assertThrows(FfmpegInstaller.BusyException.class, () -> service.start(FfmpegInstaller.ProxySettings.disabled()));
        } finally { release.countDown(); service.close(); }
    }

    @Test void cancellationWaitsForCleanupAndAnOldExecutionCannotInterruptItsSuccessor() throws Exception {
        var started = new CountDownLatch(1);
        var cleaning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = new FfmpegInstallService((proxy, progress) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            finally {
                cleaning.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        });
        try {
            var first = service.start(null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED", service.cancel(first.executionId()).state());
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED", service.cancel(first.executionId()).state());
            assertThrows(FfmpegInstaller.BusyException.class, () -> service.start(null));
            release.countDown();
            org.awaitility.Awaitility.await().until(() -> service.snapshot().state().equals("CANCELLED"));
            assertEquals("CANCELLED", service.cancel(first.executionId()).state());
            var second = service.start(null);
            assertNull(service.cancel(first.executionId()));
            assertEquals(second, service.snapshot());
            service.cancel(second.executionId());
        } finally { release.countDown(); service.close(); }
    }

    @Test void publishingTheVerifiedFilesFinishesEvenIfCancellationArrives() throws Exception {
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = new FfmpegInstallService((proxy, progress) -> {
            progress.onProgress(FfmpegInstaller.ProgressStage.INSTALLING, -1, -1);
            publishing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        try {
            var id = service.start(null).executionId();
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            assertEquals("RUNNING", service.cancel(id).state());
            release.countDown();
            service.close();
            assertEquals("COMPLETED", service.snapshot().state());
        } finally { release.countDown(); service.close(); }
    }

    @Test void failuresDoNotPublishRawExceptionText() throws Exception {
        var entered = new CountDownLatch(1);
        var service = new FfmpegInstallService((proxy, progress) -> {
            entered.countDown();
            throw new java.io.IOException("private-path-and-credential");
        });
        service.start(FfmpegInstaller.ProxySettings.disabled());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.close();
        assertEquals("FAILED", service.snapshot().state());
        assertEquals("FFMPEG_INSTALL_FAILED", service.snapshot().failureCode());
        assertFalse(service.snapshot().toString().contains("private-path"));
    }
}
