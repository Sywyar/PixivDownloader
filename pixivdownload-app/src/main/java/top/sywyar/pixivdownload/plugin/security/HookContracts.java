package top.sywyar.pixivdownload.plugin.security;

import java.nio.charset.StandardCharsets;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationHookCapabilityAdapter;

/** 发布与执行共用的纯值校验，不运行插件回调。 */
public final class HookContracts {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(24).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private HookContracts() { }

    public static OperationHook.Definition definition(OperationHook.Definition value) {
        if (value == null || value.toString().getBytes(StandardCharsets.UTF_8).length > 65536 || value.phases().isEmpty())
            throw new IllegalArgumentException("OPERATION_HOOK_INVALID");
        OperationPolicies.identity(value.revision()); OperationPolicies.schema(value.configurationSchema());
        if (!value.inputSchema().isEmpty()) OperationPolicies.schema(value.inputSchema());
        if (!value.resultSchema().isEmpty()) OperationPolicies.schema(value.resultSchema());
        if (value.protocol() != null) OperationPolicies.protocol(value.protocol());
        OperationPolicies.validate(new OperationVerification.Policy(value.revision(), value.inputSchema(),
                value.protocol() == null ? OperationVerification.INTERACTION : value.protocol(), value.resources()));
        return value;
    }

    public static void binding(OperationBinding value, OperationHook.Definition definition) {
        if (value == null || value.phase() == null || value.completion() == null
                || value.operation() == null || value.endpoint() == null
                || value.operation().isEmpty() == value.endpoint().isEmpty())
            throw new IllegalArgumentException("OPERATION_HOOK_BINDING_INVALID");
        OperationPolicies.identity(value.id()); OperationHookCapabilityAdapter.key(value.hook());
        OperationPolicies.identity(value.operation().isEmpty() ? value.endpoint() : value.operation());
        if (!definition.phases().contains(value.phase()) || value.phase() == OperationHook.Phase.BEFORE_INVOKE
                && value.completion() != OperationHook.Completion.ALL)
            throw new IllegalArgumentException("OPERATION_HOOK_PHASE_INVALID");
        json(value.configuration());
        HookFields.validate(value);
        RemoteValues.validateConfiguration(definition.configurationSchema(), value.configuration());
    }

    public static void json(String value) {
        if (value == null || value.length() > 16384 || value.getBytes(StandardCharsets.UTF_8).length > 16384)
            throw new IllegalArgumentException("OPERATION_HOOK_VALUE_LIMIT");
        try { if (JSON.readTree(value) == null) throw new IllegalArgumentException("OPERATION_HOOK_VALUE_INVALID"); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("OPERATION_HOOK_VALUE_INVALID"); }
    }
}
