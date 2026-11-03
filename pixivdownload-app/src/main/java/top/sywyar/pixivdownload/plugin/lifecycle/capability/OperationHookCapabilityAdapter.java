package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.operation.OperationHook;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.security.HookContracts;

/** Hook 与其他同步服务共用注册、撤回及真实 drain，不创建独立线程池。 */
@Component
public final class OperationHookCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private final ExternalCapabilityInvocationRegistry registry;
    public OperationHookCapabilityAdapter(ExternalCapabilityInvocationRegistry registry) { this.registry = registry; }
    private record Prepared(ExternalCapabilityOwner owner) implements PreparedContribution { }

    public static ServiceKey<OperationHook> key(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || id.length() > 96)
            throw new IllegalArgumentException("OPERATION_HOOK_ID_INVALID");
        return new ServiceKey<>("operation-hook-" + id, OperationHook.class);
    }

    @Override public String capabilityName() { return OperationHook.class.getName(); }
    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(OperationHook.class).values();
        if (beans.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
        for (var hook : beans) {
            if (hook instanceof top.sywyar.pixivdownload.plugin.api.security.OperationVerification) continue;
            String id = registry.captureMetadata(preparation, OperationHook.class, "id", hook::id);
            HookContracts.definition(registry.captureMetadata(preparation, OperationHook.class, "definition", hook::definition));
            top.sywyar.pixivdownload.plugin.security.OperationPolicies.identity(
                    registry.captureMetadata(preparation, OperationHook.class, "generation", hook::generation));
            registry.prepareService(preparation, key(id), hook);
        }
        return new Prepared(preparation.owner());
    }
    @Override public void publish(PreparedContribution contribution) { }
    @Override public void withdraw(ExternalCapabilityOwner owner) { }
}
