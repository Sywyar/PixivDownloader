package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("重复扫描异步生命周期")
class DuplicateScanServiceTest {

    private final ImageHashMapper imageHashMapper = mock(ImageHashMapper.class);
    private final ArtworkHashService artworkHashService = mock(ArtworkHashService.class);
    private final DuplicateService duplicateService = mock(DuplicateService.class);
    private final PixivDatabase pixivDatabase = mock(PixivDatabase.class);
    private final AppMessages messages = mock(AppMessages.class);

    @Test
    @DisplayName("扫描服务以 owner 队列能力贡献精确清退与取消")
    void contributesOwnerQueueDrainAndCancellation() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        when(imageHashMapper.countArtworksMissingHashes()).thenReturn(1);
        DuplicateScanService service = service(queued::set);
        QueueOperations operations = service;

        assertThat(service.startScan(false).state()).isEqualTo("RUNNING");
        assertThat(operations.queueType()).isEqualTo("duplicate-scan");
        QueueGenerationDrain drain = operations.prepareQuiesce();
        operations.cancelQuiescedTasks();

        assertThat(drain.queueType()).isEqualTo("duplicate-scan");
        assertThat(drain.isDrained()).isTrue();
        assertThat(service.status()).isEqualTo(DuplicateDto.idleScanStatus());
        assertThat(field(queued.get(), "delegate")).isNull();
        assertThat(operations.clearAll()).isZero();
        assertThat(operations.clearForOwner("visitor-owner")).isZero();
    }

    @Test
    @DisplayName("排队扫描取消后父执行器只保留不持插件 delegate 的宿主空壳")
    void queuedCancellationClearsPluginDelegate() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        when(imageHashMapper.countArtworksMissingHashes()).thenReturn(1);
        DuplicateScanService service = service(queued::set);

        assertThat(service.startScan(false).state()).isEqualTo("RUNNING");
        Runnable wrapper = queued.get();
        assertThat(wrapper).isInstanceOf(QueueTaskTracker.Task.class);

        service.destroy();

        assertThat(service.status()).isEqualTo(DuplicateDto.idleScanStatus());
        assertThat(field(wrapper, "delegate")).isNull();
        assertThat(field(wrapper, "cancellationAction")).isNull();
        wrapper.run();
        verify(imageHashMapper, never()).artworkIdsMissingHashes(anyInt());
    }

    @Test
    @DisplayName("运行扫描收到取消后按作品协作退出且销毁等待真实完成")
    void runningCancellationStopsCooperativelyAndDestroyWaits() throws Exception {
        ArtworkRecord artwork = mock(ArtworkRecord.class);
        CountDownLatch hashEntered = new CountDownLatch(1);
        CountDownLatch releaseHash = new CountDownLatch(1);
        CountDownLatch cancellationDelivered = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        when(imageHashMapper.countArtworksMissingHashes()).thenReturn(2);
        when(imageHashMapper.artworkIdsMissingHashes(Integer.MAX_VALUE)).thenReturn(List.of(1L, 2L));
        when(pixivDatabase.getArtwork(1L)).thenReturn(artwork);
        when(pixivDatabase.getArtwork(2L)).thenReturn(artwork);
        when(artworkHashService.recordArtworkHashes(artwork)).thenAnswer(invocation -> {
            hashEntered.countDown();
            if (!releaseHash.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release hash calculation");
            }
            return 1;
        });
        DuplicateScanService service = service(command -> {
            QueueTaskTracker.Task task = (QueueTaskTracker.Task) command;
            task.onCancellation(cancellationDelivered::countDown);
            Thread thread = new Thread(() -> {
                try {
                    command.run();
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            }, "duplicate-scan-test");
            worker.set(thread);
            thread.start();
        });

        service.startScan(false);
        assertThat(hashEntered.await(2, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean destroyReturned = new AtomicBoolean();
        Thread destroyer = new Thread(() -> {
            service.destroy();
            destroyReturned.set(true);
        }, "duplicate-destroy-test");
        destroyer.start();

        assertThat(cancellationDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(destroyReturned).isFalse();
        assertThat(destroyer.isAlive()).isTrue();

        releaseHash.countDown();
        destroyer.join(2_000);
        worker.get().join(2_000);

        assertThat(destroyer.isAlive()).isFalse();
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(destroyReturned).isTrue();
        assertThat(workerFailure).hasValue(null);
        verify(pixivDatabase).getArtwork(1L);
        verify(pixivDatabase, never()).getArtwork(2L);
        verify(duplicateService, never()).invalidate();
        assertThat(service.status()).isEqualTo(DuplicateDto.idleScanStatus());
    }

    @Test
    @DisplayName("父执行器拒绝提交时回滚任务与扫描状态并允许再次启动")
    void executorRejectionRollsBackTaskAndScanState() throws Exception {
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> rejected = new AtomicReference<>();
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        when(imageHashMapper.countArtworksMissingHashes()).thenReturn(0);
        when(imageHashMapper.artworkIdsMissingHashes(Integer.MAX_VALUE)).thenReturn(List.of());
        DuplicateScanService service = service(command -> {
            if (submissions.getAndIncrement() == 0) {
                rejected.set(command);
                throw rejection;
            }
            accepted.set(command);
        });

        assertThatThrownBy(() -> service.startScan(false)).isSameAs(rejection);
        assertThat(service.status().state()).isEqualTo("FAILED");
        assertThat(field(rejected.get(), "delegate")).isNull();

        assertThat(service.startScan(false).state()).isEqualTo("RUNNING");
        accepted.get().run();

        assertThat(service.status().state()).isEqualTo("DONE");
        assertThat(submissions).hasValue(2);
    }

    @Test
    @DisplayName("销毁在同步准备仍持依赖 Bean 时等待并在完成后恢复中断标志")
    void destroyWaitsForSynchronousPreparationAndRestoresInterrupt() throws Exception {
        CountDownLatch countEntered = new CountDownLatch(1);
        CountDownLatch releaseCount = new CountDownLatch(1);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        AtomicBoolean interruptedAfterDestroy = new AtomicBoolean();
        when(imageHashMapper.countArtworksMissingHashes()).thenAnswer(invocation -> {
            countEntered.countDown();
            assertThat(releaseCount.await(2, TimeUnit.SECONDS)).isTrue();
            return 0;
        });
        DuplicateScanService service = service(submitted::set);
        Thread starter = new Thread(() -> {
            try {
                service.startScan(false);
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "duplicate-start-test");
        starter.start();
        assertThat(countEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread destroyer = new Thread(() -> {
            Thread.currentThread().interrupt();
            service.destroy();
            interruptedAfterDestroy.set(Thread.currentThread().isInterrupted());
        }, "duplicate-interrupted-destroy-test");
        destroyer.start();
        QueueTaskTracker tracker = (QueueTaskTracker) field(service, "taskTracker");
        assertThat(awaitNotAccepting(tracker)).isTrue();
        assertThat(destroyer.isAlive()).isTrue();
        assertThat(submitted).hasValue(null);

        releaseCount.countDown();
        starter.join(2_000);
        destroyer.join(2_000);

        assertThat(starter.isAlive()).isFalse();
        assertThat(destroyer.isAlive()).isFalse();
        assertThat(startFailure).hasValue(null);
        assertThat(interruptedAfterDestroy).isTrue();
        assertThat(submitted).hasValue(null);
        assertThat(service.status()).isEqualTo(DuplicateDto.idleScanStatus());
    }

    private DuplicateScanService service(TaskExecutor executor) {
        return new DuplicateScanService(
                imageHashMapper, artworkHashService, duplicateService, pixivDatabase, messages, executor);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean awaitNotAccepting(QueueTaskTracker tracker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (tracker.isAccepting() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return !tracker.isAccepting();
    }
}
