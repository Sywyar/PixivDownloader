package com.example.pixivdownload.downloadtype;

import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.example.pixivdownload.downloadtype.queue.ExampleDownloadSubmission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("独立下载模板使用公开提交与事件契约")
class ExampleDownloadSubmissionTest {
    @Test
    @DisplayName("执行选项扩展并在领域动作完成后发布相同身份的完成事件")
    void contributesSubmissionAndLifecycle() {
        var queue = new ExampleDownloadQueue();
        var lifecycle = new RecordingLifecycle();
        var handler = new ExampleDownloadSubmission(queue, lifecycle);
        var command = new DownloadSubmission(UUID.randomUUID(), handler.workType(), "123", Map.of());
        var attempt = new DownloadAttempt(UUID.randomUUID(), command.workType(), command.workId());
        handler.submit(command, attempt, null);
        assertEquals("Example 123", queue.find("123", RequestOwnerIdentity.adminScope()).orElseThrow().title());
        assertEquals(List.of(DownloadEvent.Phase.STARTED, DownloadEvent.Phase.COMPLETED),
                lifecycle.events.stream().map(DownloadEvent::phase).toList());
        assertTrue(lifecycle.events.stream().allMatch(event -> event.attempt().equals(attempt)));
        assertTrue(lifecycle.exited);
        assertThrows(IllegalArgumentException.class, () -> handler.submit(
                new DownloadSubmission(UUID.randomUUID(), handler.workType(), "456", Map.of("root", "/tmp")),
                new DownloadAttempt(UUID.randomUUID(), handler.workType(), "456"), null));
        assertTrue(queue.find("456", RequestOwnerIdentity.adminScope()).isEmpty());
    }

    static final class RecordingLifecycle implements DownloadLifecycle {
        final List<DownloadEvent> events = new ArrayList<>();
        boolean exited;
        @Override public void register(DownloadAttempt attempt, String owner, String type, String title) {}
        @Override public void track(DownloadAttempt attempt, QueueTaskTracker.Task task, String owner,
                                    String type, String title, boolean queued) {
            task.onTermination(() -> exited = true);
        }
        @Override public Map<String, String> options(DownloadAttempt attempt, Map<String, String> options) {
            return new ExampleDownloadConfiguration().exampleTitleHook().customize(attempt, options);
        }
        @Override public void checkAdmission(DownloadAttempt attempt) {}
        @Override public void publish(DownloadEvent event) { events.add(event); }
    }
}
