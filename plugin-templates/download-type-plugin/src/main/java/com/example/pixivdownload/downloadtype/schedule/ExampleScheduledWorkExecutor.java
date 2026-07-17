package com.example.pixivdownload.downloadtype.schedule;

import com.example.pixivdownload.downloadtype.ExampleDownloadPlugin;
import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

/** Synchronous example work execution that completes only after its in-memory domain fact is written. */
@PluginManagedBean
public final class ExampleScheduledWorkExecutor implements ScheduledWorkExecutor {

    private final ObjectMapper objectMapper;
    private final ExampleDownloadQueue queue;

    public ExampleScheduledWorkExecutor(ObjectMapper objectMapper, ExampleDownloadQueue queue) {
        this.objectMapper = objectMapper;
        this.queue = queue;
    }

    @Override
    public String workType() {
        return ExampleDownloadPlugin.TYPE;
    }

    @Override
    public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException {
        if (!ExampleDownloadPlugin.TYPE.equals(work.key().workType())
                || !ExampleScheduledSourceExecutor.PAYLOAD_SCHEMA.equals(work.payloadSchema())
                || work.payloadVersion() != 1) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "example.payload-envelope-unsupported");
        }
        ExampleScheduledSourceExecutor.Definition definition;
        try {
            definition = objectMapper.readValue(
                    work.payloadJson(), ExampleScheduledSourceExecutor.Definition.class);
        } catch (JsonProcessingException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "example.payload-json-invalid");
        }
        if (definition.id() == null || !definition.id().matches("[0-9]{1,18}")) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "example.payload-id-invalid");
        }
        if (!definition.id().equals(work.key().id())) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "example.payload-key-mismatch");
        }
        context.cancellation().throwIfCancellationRequested();
        queue.completeForOwner(
                definition.id(),
                "Example " + definition.id(),
                "schedule:" + context.task().taskId());
        return ScheduledWorkResult.completed();
    }
}
