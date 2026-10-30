package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.security.LoginVerification;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 安全接缝进入既有服务事实源，发布与撤回由同一 owner 批次完成。 */
@Component
public final class LoginVerificationCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private final ExternalCapabilityInvocationRegistry registry;
    public LoginVerificationCapabilityAdapter(ExternalCapabilityInvocationRegistry registry) { this.registry = registry; }
    private record Prepared(ExternalCapabilityOwner owner) implements PreparedContribution { }

    public static ServiceKey<LoginVerification> key(String policyId) {
        if (policyId == null || policyId.length() > 96 || !policyId.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("LOGIN_POLICY_INVALID");
        }
        return new ServiceKey<>("host-login-" + policyId, LoginVerification.class);
    }

    @Override public String capabilityName() { return LoginVerification.class.getName(); }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(LoginVerification.class);
        if (beans.size() > 16) throw new IllegalArgumentException("LOGIN_POLICY_CAPACITY");
        for (var bean : beans.values()) {
            String id = registry.captureMetadata(preparation, LoginVerification.class, "policyId", bean::policyId);
            registry.prepareService(preparation, key(id), bean);
        }
        return new Prepared(preparation.owner());
    }

    @Override public void publish(PreparedContribution contribution) { }
    @Override public void withdraw(ExternalCapabilityOwner owner) { }
}
