package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.util.ArrayDeque;
import java.util.Objects;

import static top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionSafety.*;

/** 计划凭证的装载、探活、绑定结果净化与持久化材料检查。 */
final class ScheduleCredentialSupport {

    private final ScheduledTaskStore store;
    private final ObjectMapper objectMapper;

    ScheduleCredentialSupport(ScheduledTaskStore store, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    ScheduleCredentialMaterial load(
            ScheduledTask task,
            ScheduleExecutionLease execution,
            ScheduledExecutionPlan plan) {
        ScheduleCapabilityOwner actualOwner = execution.credentialPolicyOwner().orElse(null);
        boolean bindingMatches = actualOwner != null
                && Objects.equals(task.credentialPolicyOwnerPluginId(), actualOwner.featurePluginId())
                && Objects.equals(task.credentialPolicyId(), plan.credentialPolicyId())
                && task.credentialSecretReference() != null;
        String secret = bindingMatches
                ? store.findCredentialSecret(
                        task.id(), actualOwner.featurePluginId(), plan.credentialPolicyId())
                : null;
        return new ScheduleCredentialMaterial(
                secret,
                bindingMatches ? task.credentialSecretReference() : null,
                bindingMatches ? task.credentialAccountKey() : null);
    }

    ProbeOutcome probeForRun(
            ScheduledTask task,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduledCancellation cancellation,
            ScheduleCredentialMaterial credential,
            ScheduleExecutionLease execution,
            ScheduledExecutionPlan plan)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        cancellation.throwIfCancellationRequested();
        if (plan.credentialRequirement() == ScheduledCredentialRequirement.NONE) {
            return ProbeOutcome.KEPT;
        }
        if (!credential.isPresent()) {
            if (plan.credentialRequirement() == ScheduledCredentialRequirement.REQUIRED) {
                throw control(
                        ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                        "schedule.credential.required",
                        0L,
                        ScheduledGuardEvidence.empty());
            }
            return ProbeOutcome.KEPT;
        }
        var policy = execution.credentialPolicy().orElseThrow(() -> new ScheduledExecutionException(
                ScheduledFailure.Category.INTERNAL, "schedule.credential.policy-unavailable"));
        ScheduledCredentialProbeResult probe;
        try (var handle = credential.openHandle()) {
            ScheduledCredentialContext context = new ScheduledCredentialContext() {
                @Override
                public Purpose purpose() {
                    return Purpose.RUN_START;
                }

                @Override
                public ScheduledTaskDefinition task() {
                    return definition;
                }

                @Override
                public ScheduledNetworkRoute route() {
                    return route;
                }

                @Override
                public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                    return handle;
                }

                @Override
                public ScheduledCancellation cancellation() {
                    return cancellation;
                }
            };
            try {
                probe = policy.probe(context);
            } catch (ScheduledExecutionException failure) {
                throw safePluginException(
                        failure, "schedule.credential.probe-failed", credential);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw pluginFailure("schedule.credential.probe-failed");
            }
            if (probe == null) {
                throw pluginFailure("schedule.credential.null-result");
            }
            if (!isSafeMachineCode(probe.code())
                    || !isSafeAccountKey(probe.accountKey())
                    || credential.containsEcho(probe.code())
                    || credential.containsEcho(probe.accountKey())) {
                throw pluginFailure("schedule.credential.invalid-result");
            }
            probe = new ScheduledCredentialProbeResult(
                    probe.status(), probe.accountKey(), probe.code(), probe.retryAfterMillis());
        }
        return switch (probe.status()) {
            case VALID -> {
                if (task.credentialAccountKey() != null
                        && !Objects.equals(task.credentialAccountKey(), probe.accountKey())) {
                    throw control(
                            ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                            "schedule.credential.account-mismatch",
                            0L,
                            ScheduledGuardEvidence.empty());
                }
                credential.setAccountKey(probe.accountKey());
                yield ProbeOutcome.KEPT;
            }
            case INVALID -> {
                if (plan.anonymousFallbackAllowed()) {
                    credential.revoke();
                    yield ProbeOutcome.REVOKED;
                }
                throw control(
                        ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                        probe.code(),
                        0L,
                        ScheduledGuardEvidence.empty());
            }
            case RETRY_LATER -> throw control(
                    ScheduledGuardDecision.Action.RETRY_LATER,
                    probe.code(),
                    probe.retryAfterMillis(),
                    ScheduledGuardEvidence.empty());
        };
    }

    ScheduledCredentialBindResult probeForBinding(
            long taskId,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduleExecutionLease execution,
            String candidateSecret) throws ScheduledExecutionException {
        ScheduledCancellation cancellation = execution.cancellation();
        cancellation.throwIfCancellationRequested();
        var policy = execution.credentialPolicy().orElseThrow(() -> pluginFailure(
                "schedule.credential.policy-unavailable"));
        try (ScheduleCredentialMaterial credential = new ScheduleCredentialMaterial(
                candidateSecret, "scheduled-task:" + taskId + ":credential", null);
             var handle = credential.openHandle()) {
            ScheduledCredentialContext context = new ScheduledCredentialContext() {
                @Override
                public Purpose purpose() {
                    return Purpose.BIND;
                }

                @Override
                public ScheduledTaskDefinition task() {
                    return definition;
                }

                @Override
                public ScheduledNetworkRoute route() {
                    return route;
                }

                @Override
                public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                    return handle;
                }

                @Override
                public ScheduledCancellation cancellation() {
                    return cancellation;
                }
            };
            ScheduledCredentialBindResult result;
            try {
                result = policy.probeForBinding(context);
            } catch (ScheduledExecutionException failure) {
                throw safePluginException(
                        failure, "schedule.credential.bind-probe-failed", credential);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                throw pluginFailure("schedule.credential.bind-probe-failed");
            }
            cancellation.throwIfCancellationRequested();
            return validateBindResult(result, credential);
        }
    }

    void validateStoredArtifacts(
            ScheduledTask task,
            ScheduledCheckpoint storedCheckpoint,
            ScheduleCredentialMaterial credential) throws ScheduledExecutionException {
        if (storedCheckpoint != null
                && (credential.containsEcho(storedCheckpoint.schema())
                    || credential.containsEchoInJson(
                        objectMapper, storedCheckpoint.payloadJson()))) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "schedule.checkpoint.payload-invalid");
        }
        String policyStateJson = task.credentialPolicyStateJson();
        if (policyStateJson != null
                && credential.containsEchoInJson(objectMapper, policyStateJson)) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
    }

    private ScheduledCredentialBindResult validateBindResult(
            ScheduledCredentialBindResult result,
            ScheduleCredentialMaterial credential) throws ScheduledExecutionException {
        if (result == null || result.probeResult() == null
                || !isSafeMachineCode(result.probeResult().code())
                || !isSafeAccountKey(result.probeResult().accountKey())
                || credential.containsEcho(result.probeResult().code())
                || credential.containsEcho(result.probeResult().accountKey())) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
        ScheduledGuardDecision decision = result.postBindResult().decision();
        if (decision.action() != ScheduledGuardDecision.Action.CONTINUE
                && (!isSafeMachineCode(decision.reasonCode())
                || credential.containsEcho(decision.reasonCode()))) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
        try {
            String initialPolicyStateJson = validateInitialPolicyState(
                    result.initialPolicyStateJson(), credential);
            ScheduledCredentialProbeResult probe = new ScheduledCredentialProbeResult(
                    result.probeResult().status(), result.probeResult().accountKey(),
                    result.probeResult().code(), result.probeResult().retryAfterMillis());
            ScheduledGuardResult postBind = new ScheduledGuardResult(
                    new ScheduledGuardDecision(
                            decision.action(), decision.reasonCode(), decision.retryAfterMillis()),
                    sanitizeEvidence(
                            result.postBindResult().evidence(), credential,
                            "schedule.credential.invalid-bind-result"));
            return new ScheduledCredentialBindResult(
                    probe, initialPolicyStateJson, postBind);
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            throw pluginFailure("schedule.credential.invalid-bind-result");
        }
    }

    private String validateInitialPolicyState(
            String initialPolicyStateJson,
            ScheduleCredentialMaterial credential)
            throws ScheduledExecutionException {
        if (credential.containsEchoInJson(objectMapper, initialPolicyStateJson)) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
        ObjectReader strictReader = objectMapper.readerFor(JsonNode.class).with(
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = readStrictPolicyJson(strictReader, initialPolicyStateJson);
        if (root == null || !root.isObject()) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (credential.containsEcho(field.getKey())
                            || ScheduleCredentialRedactor.isSensitiveFieldName(field.getKey())
                            || (ScheduleCredentialRedactor.isSensitiveMetadataFieldName(field.getKey())
                            && (!field.getValue().isValueNode()
                            || field.getValue().isNull()
                            || !ScheduleCredentialRedactor.isSafeMetadataValue(
                            field.getKey(), field.getValue().asText())))) {
                        throw pluginFailure("schedule.credential.invalid-policy-state");
                    }
                    pending.addLast(field.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::addLast);
            } else if (node.isTextual()) {
                String text = node.textValue();
                if (credential.containsEcho(text)) {
                    throw pluginFailure("schedule.credential.invalid-policy-state");
                }
                JsonNode embedded = readEmbeddedPolicyJson(strictReader, text);
                if (embedded != null) {
                    pending.addLast(embedded);
                } else if (ScheduleCredentialRedactor.containsCredentialMaterial(text)) {
                    throw pluginFailure("schedule.credential.invalid-policy-state");
                }
            } else if (node.isValueNode()
                    && !node.isNull()
                    && credential.containsEcho(node.asText())) {
                throw pluginFailure("schedule.credential.invalid-policy-state");
            }
        }
        return initialPolicyStateJson;
    }

    private JsonNode readStrictPolicyJson(ObjectReader strictReader, String json)
            throws ScheduledExecutionException {
        try {
            return strictReader.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            throw pluginFailure("schedule.credential.invalid-policy-state");
        }
    }

    private JsonNode readEmbeddedPolicyJson(ObjectReader strictReader, String text)
            throws ScheduledExecutionException {
        String candidate = text == null ? "" : text.trim();
        if (!candidate.startsWith("{") && !candidate.startsWith("[")) {
            return null;
        }
        try {
            JsonNode nested = strictReader.readTree(candidate);
            return nested != null && (nested.isObject() || nested.isArray()) ? nested : null;
        } catch (JsonProcessingException | IllegalArgumentException strictFailure) {
            try {
                JsonNode permissive = objectMapper.readTree(candidate);
                if (permissive != null && (permissive.isObject() || permissive.isArray())) {
                    throw pluginFailure("schedule.credential.invalid-policy-state");
                }
            } catch (ScheduledExecutionException failure) {
                throw failure;
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
                // 以花括号开头的普通文本不是嵌套 JSON，不按策略状态解释。
            }
            return null;
        }
    }

    enum ProbeOutcome {
        KEPT(false),
        REVOKED(true);

        private final boolean revoked;

        ProbeOutcome(boolean revoked) {
            this.revoked = revoked;
        }

        boolean revoked() {
            return revoked;
        }
    }
}
