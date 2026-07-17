package com.example.pixivdownload.downloadtype.schedule;

import com.example.pixivdownload.downloadtype.ExampleDownloadPlugin;
import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleScheduleTest {

    @Test
    @DisplayName("凭证无关来源发现单件作品并同步完成领域动作")
    void credentialFreeSourceDiscoversAndExecutesOneWork() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ExampleScheduledSourceExecutor source = new ExampleScheduledSourceExecutor(objectMapper);
        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        ExampleScheduledWorkExecutor workExecutor = new ExampleScheduledWorkExecutor(objectMapper, queue);
        ScheduledTaskDefinition task = new ScheduledTaskDefinition(
                7L,
                ExampleScheduledSourceExecutor.SOURCE_TYPE,
                ExampleScheduledSourceExecutor.DEFINITION_SCHEMA,
                1,
                "{\"id\":\"123\"}",
                ScheduledTaskPresentation.empty());

        assertEquals(java.util.Set.of(ExampleDownloadPlugin.TYPE),
                source.plan(task).requiredWorkTypes());
        AtomicReference<ScheduledWork> discovered = new AtomicReference<>();
        source.discover(new SourceContext(task, discovered::set));
        assertEquals("123", discovered.get().key().id());

        ScheduledWorkResult result = workExecutor.execute(discovered.get(), new WorkContext(task));
        assertEquals(ScheduledWorkResult.Outcome.COMPLETED, result.outcome());
        assertEquals(1, queue.snapshot().size());
        assertEquals("123", queue.snapshot().get(0).id());
        assertEquals("schedule:7", queue.snapshot().get(0).ownerKey());
    }

    private abstract static class BaseContext {
        private final ScheduledTaskDefinition task;

        BaseContext(ScheduledTaskDefinition task) {
            this.task = task;
        }

        public ScheduledTaskDefinition task() {
            return task;
        }

        public ScheduledNetworkRoute route() {
            return ScheduledNetworkRoute.direct();
        }

        public ScheduledCredentialHandle credential() {
            return null;
        }

        public ScheduledCancellation cancellation() {
            return () -> false;
        }
    }

    private static final class SourceContext extends BaseContext implements ScheduledSourceContext {
        private final ScheduledWorkSink sink;

        private SourceContext(ScheduledTaskDefinition task, ScheduledWorkSink sink) {
            super(task);
            this.sink = sink;
        }

        @Override
        public ScheduledCheckpoint checkpoint() {
            return null;
        }

        @Override
        public ScheduledWorkSink workSink() {
            return sink;
        }
    }

    private static final class WorkContext extends BaseContext implements ScheduledWorkContext {
        private WorkContext(ScheduledTaskDefinition task) {
            super(task);
        }
    }
}
