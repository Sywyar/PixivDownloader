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
    private final top.sywyar.pixivdownload.plugin.security.HttpHookDeclarations declarations;
    public OperationHookCapabilityAdapter(ExternalCapabilityInvocationRegistry registry,
            top.sywyar.pixivdownload.plugin.security.HttpHookDeclarations declarations) {
        this.registry = registry; this.declarations = declarations;
    }
    private record Prepared(ExternalCapabilityOwner owner,
            java.util.List<top.sywyar.pixivdownload.plugin.security.HttpHookDeclarations.Declaration> declarations) implements PreparedContribution { }

    public static ServiceKey<OperationHook> key(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || id.length() > 96)
            throw new IllegalArgumentException("OPERATION_HOOK_ID_INVALID");
        return new ServiceKey<>("operation-hook-" + id, OperationHook.class);
    }

    @Override public String capabilityName() { return OperationHook.class.getName(); }
    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(OperationHook.class).values();
        var methods = HttpHookMethods.find(context, preparation, registry);
        long ordinary = beans.stream().filter(bean -> methods.stream().noneMatch(method -> method.replacesHook() && method.bean() == bean)).count();
        if (ordinary + methods.size() > 16) throw new IllegalArgumentException("OPERATION_HOOK_CAPACITY");
        for (var hook : beans) {
            if (hook instanceof top.sywyar.pixivdownload.plugin.api.security.OperationVerification) continue;
            if (methods.stream().anyMatch(method -> method.replacesHook() && method.bean() == hook)) continue;
            String id = registry.captureMetadata(preparation, OperationHook.class, "id", hook::id);
            HookContracts.definition(registry.captureMetadata(preparation, OperationHook.class, "definition", hook::definition));
            top.sywyar.pixivdownload.plugin.security.OperationPolicies.identity(
                    registry.captureMetadata(preparation, OperationHook.class, "generation", hook::generation));
            registry.prepareService(preparation, key(id), hook);
        }
        for (var method : methods) {
            top.sywyar.pixivdownload.plugin.security.OperationPolicies.identity(
                    registry.captureMetadata(preparation, OperationHook.class, "generation", method.hook()::generation));
            registry.prepareService(preparation, key(method.hook().id()), method.hook(), method.declaration().callback());
        }
        return new Prepared(preparation.owner(), methods.stream().map(HttpHookMethods.Found::declaration).toList());
    }
    @Override public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) throw new IllegalArgumentException("OPERATION_HOOK_DECLARATION_INVALID");
        declarations.publish(prepared.owner(), prepared.declarations());
    }
    @Override public void withdraw(ExternalCapabilityOwner owner) { declarations.withdraw(owner); }
}
