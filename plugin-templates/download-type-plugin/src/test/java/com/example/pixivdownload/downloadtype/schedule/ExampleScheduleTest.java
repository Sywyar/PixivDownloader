package com.example.pixivdownload.downloadtype.schedule;

import com.example.pixivdownload.downloadtype.ExampleDownloadPlugin;
import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        source.discover(new SourceContext(task, recordingSink(discovered)));
        assertEquals("123", discovered.get().key().id());

        ScheduledWorkResult result = workExecutor.execute(discovered.get(), new WorkContext(task));
        assertEquals(ScheduledWorkResult.Outcome.COMPLETED, result.outcome());
        assertEquals(1, queue.snapshot().size());
        assertEquals("123", queue.snapshot().get(0).id());
        assertEquals("schedule:7", queue.snapshot().get(0).ownerKey());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "null|example.definition-id-invalid",
            "{}|example.definition-id-invalid",
            "{\"id\":null}|example.definition-id-invalid",
            "{\"id\":\"bad\"}|example.definition-id-invalid",
            "{\"id\":\"1234567890123456789\"}|example.definition-id-invalid",
            "{|example.definition-json-invalid"
    })
    @DisplayName("非法定义在规划与发现时返回明确失败且不提交作品")
    void invalidDefinitionFailsBeforeSubmittingWork(String json, String expectedCode) {
        ExampleScheduledSourceExecutor source = new ExampleScheduledSourceExecutor(new ObjectMapper());
        ScheduledTaskDefinition task = task(json);
        AtomicReference<ScheduledWork> discovered = new AtomicReference<>();

        assertAll(
                () -> {
                    ScheduledExecutionException failure = assertThrows(
                            ScheduledExecutionException.class, () -> source.plan(task));
                    assertEquals(ScheduledFailure.Category.INVALID_DEFINITION, failure.category());
                    assertEquals(expectedCode, failure.code());
                },
                () -> {
                    ScheduledExecutionException failure = assertThrows(ScheduledExecutionException.class,
                            () -> source.discover(new SourceContext(task, recordingSink(discovered))));
                    assertEquals(ScheduledFailure.Category.INVALID_DEFINITION, failure.category());
                    assertEquals(expectedCode, failure.code());
                });
        assertNull(discovered.get());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "null|example.payload-id-invalid",
            "{}|example.payload-id-invalid",
            "{\"id\":null}|example.payload-id-invalid",
            "{\"id\":\"bad\"}|example.payload-id-invalid",
            "{\"id\":\"1234567890123456789\"}|example.payload-id-invalid",
            "{|example.payload-json-invalid",
            "{\"id\":\"124\"}|example.payload-key-mismatch"
    })
    @DisplayName("非法作品载荷返回明确失败且不写入下载队列")
    void invalidPayloadFailsBeforeWritingQueue(String json, String expectedCode) {
        ExampleDownloadQueue queue = new ExampleDownloadQueue();
        ExampleScheduledWorkExecutor executor = new ExampleScheduledWorkExecutor(new ObjectMapper(), queue);
        ScheduledWork work = new ScheduledWork(new ScheduledWorkKey(ExampleDownloadPlugin.TYPE, "123"),
                ExampleScheduledSourceExecutor.PAYLOAD_SCHEMA, 1, json, null, null);

        ScheduledExecutionException failure = assertThrows(ScheduledExecutionException.class,
                () -> executor.execute(work, new WorkContext(task("{\"id\":\"123\"}"))));

        assertEquals(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED, failure.category());
        assertEquals(expectedCode, failure.code());
        assertTrue(queue.snapshot().isEmpty());
    }

    private static ScheduledTaskDefinition task(String json) {
        return new ScheduledTaskDefinition(7L, ExampleScheduledSourceExecutor.SOURCE_TYPE,
                ExampleScheduledSourceExecutor.DEFINITION_SCHEMA, 1, json, ScheduledTaskPresentation.empty());
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

        @Override
        public boolean isPending(ScheduledWorkKey key) {
            return false;
        }
    }

    private static final class WorkContext extends BaseContext implements ScheduledWorkContext {
        private WorkContext(ScheduledTaskDefinition task) {
            super(task);
        }
    }

    private static ScheduledWorkSink recordingSink(
            AtomicReference<ScheduledWork> discovered) {
        return new ScheduledWorkSink() {
            @Override
            public void submit(ScheduledWork work) {
                discovered.set(work);
            }

            @Override
            public void completeLocally(ScheduledWork work, ScheduledWorkResult result) {
                discovered.set(work);
            }

            @Override
            public void drain() {
            }
        };
    }
}
