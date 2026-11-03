package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;
import top.sywyar.pixivdownload.plugin.security.OperationPolicies;

/** 安全接缝进入既有服务事实源，发布与撤回由同一 owner 批次完成。 */
@Component
public final class OperationVerificationCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private final ExternalCapabilityInvocationRegistry registry;
    public OperationVerificationCapabilityAdapter(ExternalCapabilityInvocationRegistry registry) { this.registry = registry; }
    private record Prepared(ExternalCapabilityOwner owner) implements PreparedContribution { }

    public static ServiceKey<OperationVerification> key(String policyId) {
        if (policyId == null || policyId.length() > 96 || !policyId.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("OPERATION_POLICY_INVALID");
        }
        return new ServiceKey<>("operation-verification-" + policyId, OperationVerification.class);
    }

    @Override public String capabilityName() { return OperationVerification.class.getName(); }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(OperationVerification.class);
        if (beans.size() > 16) throw new IllegalArgumentException("OPERATION_POLICY_CAPACITY");
        for (var bean : beans.values()) {
            String id = registry.captureMetadata(preparation, OperationVerification.class, "policyId", bean::policyId);
            OperationPolicies.validate(registry.captureMetadata(preparation, OperationVerification.class, "policy", bean::policy));
            OperationPolicies.identity(registry.captureMetadata(preparation, OperationVerification.class, "generation", bean::generation));
            registry.prepareService(preparation, key(id), bean);
            registry.prepareService(preparation, OperationHookCapabilityAdapter.key(id), bean);
        }
        return new Prepared(preparation.owner());
    }

    @Override public void publish(PreparedContribution contribution) { }
    @Override public void withdraw(ExternalCapabilityOwner owner) { }
}
