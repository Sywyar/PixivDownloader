package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;

/**
 * 宿主拥有的有界、可背压作品入口。{@link #submit(ScheduledWork)} 可以阻塞；来源不得先把无限分页积到内存。
 */
@FunctionalInterface
public interface ScheduledWorkSink {

    void submit(ScheduledWork work) throws ScheduledExecutionException;
}
