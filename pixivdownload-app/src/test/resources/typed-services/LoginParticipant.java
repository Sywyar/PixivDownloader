package acceptance.login;

import java.util.List;
import org.pf4j.*;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

/** 中性挑战夹具；不实现验证码算法，也不接触密码或会话。 */
public final class LoginParticipant extends Plugin implements PixivPluginProvider {
    public static volatile String mode = "approve";
    public LoginParticipant(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        String owner = getWrapper().getPluginId();
        return new PixivFeaturePlugin() {
            public String id() { return owner; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<WebUiSlotContribution> uiSlots() {
                return List.of(new WebUiSlotContribution(owner + ".proof", "login.verification", "/" + owner + "/proof.js", 0, java.util.Map.of(), "",
                        new top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction.Participant("neutral-policy", OperationVerification.INTERACTION)));
            }
            public List<WebRouteContribution> routes() { return List.of(WebRouteContribution.publicRoute("/" + owner + "/**")); }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/proof/", "/" + owner + "/"));
            }
        };
    }
    @Configuration(proxyBeanMethods=false)
    public static class Wiring {
        @Bean OperationVerification verification() {
            return new OperationVerification() {
                public String policyId() { return "neutral-policy"; }
                public String generation() { return "fixture-v1"; }
                public Policy policy() { return new Policy("p1", "", INTERACTION, java.util.List.of()); }
                public OperationVerificationResult verify(OperationVerificationRequest request) {
                    return switch(mode) {
                        case "fail" -> throw new IllegalStateException("private-verification-details");
                        case "reject" -> new OperationVerificationResult(OperationVerificationResult.Status.REJECTED, "");
                        case "challenge" -> new OperationVerificationResult(!request.response() ? OperationVerificationResult.Status.CHALLENGE
                            : request.proof().equals("neutral-proof") ? OperationVerificationResult.Status.APPROVED : OperationVerificationResult.Status.REJECTED,
                            "neutral-challenge");
                        default -> new OperationVerificationResult(OperationVerificationResult.Status.APPROVED, "");
                    };
                }
            };
        }
    }
}
