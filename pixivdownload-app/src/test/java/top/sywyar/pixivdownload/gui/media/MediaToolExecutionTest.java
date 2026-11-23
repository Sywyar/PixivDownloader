package top.sywyar.pixivdownload.gui.media;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaToolExecutionTest {
    private static final Status RUNNING = new Status("running", 1, 0, 0, List.of());

    @Test void oldCancellationCannotReachReplacementExecution() {
        var source = mock(Source.class);
        when(source.start(anyString())).thenReturn(new Result<>(RUNNING, null));
        when(source.status()).thenReturn(RUNNING);
        var execution = MediaToolExecution.wrap(source);
        execution.start("first");
        var old = execution.snapshot().executionId();
        execution.start("second");
        assertFalse(execution.cancel(old));
        verify(source, never()).cancel();
        assertTrue(execution.cancel(execution.snapshot().executionId()));
        verify(source).cancel();
    }

    @Test void previewInvalidatesOldCancellationAndRejectsConcurrentStart() throws Exception {
        var source = mock(Source.class);
        when(source.start(anyString())).thenReturn(new Result<>(RUNNING, null));
        when(source.status()).thenReturn(RUNNING);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(source.preview(any())).thenAnswer(call -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException cancelled) { interrupted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return new Result<>(new Preview("preview", List.of(), 0, 0, false), null);
        });
        var execution = MediaToolExecution.wrap(source);
        execution.start("first");
        var old = execution.snapshot().executionId();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            when(source.status()).thenReturn(new Status("completed", 1, 1, 0, List.of()));
            var future = pool.submit(() -> {
                var preview = execution.preview(new Request("png", "", false));
                assertFalse(Thread.currentThread().isInterrupted());
                return preview;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(execution.cancel(old));
                assertTrue(execution.snapshot().previewing());
                assertTrue(execution.cancel(execution.snapshot().executionId()));
                verify(source).cancel();
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertNotNull(execution.start("second").error());
                verify(source, never()).start("second");
            } finally { release.countDown(); }
            assertEquals("preview", future.get(5, TimeUnit.SECONDS).value().token());
        }
    }

    @Test void completionCapabilitySurvivesWrapping() {
        var source = mock(CompletionSource.class);
        when(source.start(anyString(), any())).thenAnswer(call -> {
            java.util.function.Consumer<Status> completion = call.getArgument(1);
            completion.accept(new Status("completed", 1, 1, 0, List.of()));
            return new Result<>(RUNNING, null);
        });
        var execution = MediaToolExecution.wrap(source);
        var completed = new java.util.concurrent.atomic.AtomicReference<Status>();
        ((CompletionSource) execution).start("preview", completed::set);
        assertEquals("completed", completed.get().state());
        assertNotNull(execution.snapshot().executionId());
    }
}
