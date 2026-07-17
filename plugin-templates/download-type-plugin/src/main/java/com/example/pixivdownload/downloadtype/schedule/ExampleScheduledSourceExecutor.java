package com.example.pixivdownload.downloadtype.schedule;

import com.example.pixivdownload.downloadtype.ExampleDownloadPlugin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Credential-free source that discovers exactly one deterministic work item. */
@PluginManagedBean
public final class ExampleScheduledSourceExecutor implements ScheduledSourceExecutor {

    public static final String SOURCE_TYPE = "example-download.ids";
    public static final String DEFINITION_SCHEMA = "example-download.schedule.definition";
    public static final String PAYLOAD_SCHEMA = "example-download.schedule.work";

    private static final String FRONTEND_MODULE_URL =
            "/example-download/example-download-schedule-sources.js";

    private final ObjectMapper objectMapper;

    public ExampleScheduledSourceExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ScheduledSourceDescriptor descriptor() {
        return new ScheduledSourceDescriptor(
                SOURCE_TYPE,
                Set.of(),
                DEFINITION_SCHEMA,
                1,
                new ScheduledSourcePresentation(
                        ExampleDownloadPlugin.NAMESPACE,
                        "schedule.source.name",
                        "schedule.source.description",
                        "schedule",
                        "green"),
                Set.of("single-import"),
                Set.of(ExampleDownloadPlugin.TYPE),
                Set.of(),
                Set.of(),
                new ScheduledSourceFrontendContribution(
                        ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION,
                        FRONTEND_MODULE_URL));
    }

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) throws ScheduledExecutionException {
        validateTaskEnvelope(task);
        readDefinition(task.definitionJson());
        return ScheduledExecutionPlan.credentialFree(Set.of(ExampleDownloadPlugin.TYPE));
    }

    @Override
    public ScheduledDiscoveryResult discover(ScheduledSourceContext context)
            throws ScheduledExecutionException {
        validateTaskEnvelope(context.task());
        Definition definition = readDefinition(context.task().definitionJson());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException failure) {
            throw invalidDefinition("example.definition-encoding-failed");
        }
        context.workSink().submit(new ScheduledWork(
                new ScheduledWorkKey(ExampleDownloadPlugin.TYPE, definition.id()),
                PAYLOAD_SCHEMA,
                1,
                payload,
                new ScheduledWorkPresentation(
                        "Example " + definition.id(),
                        "fixture",
                        null,
                        Map.of("source", SOURCE_TYPE)),
                List.of()));
        return ScheduledDiscoveryResult.withoutCheckpoint();
    }

    private Definition readDefinition(String json) throws ScheduledExecutionException {
        try {
            Definition definition = objectMapper.readValue(json, Definition.class);
            if (definition.id() == null || !definition.id().matches("[0-9]{1,18}")) {
                throw invalidDefinition("example.definition-id-invalid");
            }
            return definition;
        } catch (JsonProcessingException failure) {
            throw invalidDefinition("example.definition-json-invalid");
        }
    }

    private static void validateTaskEnvelope(ScheduledTaskDefinition task)
            throws ScheduledExecutionException {
        if (!SOURCE_TYPE.equals(task.sourceType())
                || !DEFINITION_SCHEMA.equals(task.definitionSchema())
                || task.definitionVersion() != 1) {
            throw invalidDefinition("example.definition-envelope-unsupported");
        }
    }

    private static ScheduledExecutionException invalidDefinition(String code) {
        return new ScheduledExecutionException(ScheduledFailure.Category.INVALID_DEFINITION, code);
    }

    public record Definition(String id) {
    }
}
