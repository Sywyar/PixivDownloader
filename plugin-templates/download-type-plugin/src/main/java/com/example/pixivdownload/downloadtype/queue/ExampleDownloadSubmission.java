package com.example.pixivdownload.downloadtype.queue;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadLifecycle;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

/** 同步示例：接口实现由 child context 注册，不要求继承 SDK 基类。 */
@PluginManagedBean
public final class ExampleDownloadSubmission implements DownloadSubmissionHandler {
    private final ExampleDownloadQueue queue;
    private final DownloadLifecycle lifecycle;
    private final QueueTaskTracker tracker = new QueueTaskTracker(ExampleDownloadQueue.QUEUE_TYPE);

    public ExampleDownloadSubmission(ExampleDownloadQueue queue, DownloadLifecycle lifecycle) {
        this.queue = queue;
        this.lifecycle = lifecycle;
    }

    @Override
    public String workType() { return ExampleDownloadQueue.QUEUE_TYPE; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        if (!submission.workType().equals(workType()) || !submission.workId().matches("[0-9]{1,18}"))
            throw new IllegalArgumentException("invalid example work identity");
        var options = lifecycle.options(attempt, submission.options());
        if (options.keySet().stream().anyMatch(key -> !key.equals("title")))
            throw new IllegalArgumentException("unsupported example option");
        String title = options.getOrDefault("title", "Example " + submission.workId());
        var task = tracker.beginRunning(null);
        try {
            lifecycle.track(attempt, task, null, workType(), title, false);
            lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
            if (task.publishIfActive(() ->
                    queue.complete(submission.workId(), title, RequestOwnerIdentity.adminScope()))) {
                lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
            }
        } finally {
            // 若业务未发布终态，宿主在真实执行退出时补充失败或取消事实。
            task.completeRunning();
        }
    }
}
