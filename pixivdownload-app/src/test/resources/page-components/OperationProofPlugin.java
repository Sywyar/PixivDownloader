package example.operationproof;

import org.pf4j.*;
import org.springframework.context.annotation.Bean;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 只验证通用接缝的中性 proof；不作为验证码防护证明。 */
public final class OperationProofPlugin extends Plugin implements PixivPluginProvider {
    public static final AtomicInteger issued = new AtomicInteger(), verified = new AtomicInteger();
    public static volatile String generation = "one", revision = "one", mode = "normal";
    public OperationProofPlugin(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "operation-proof"; }
            public String displayName() { return "name"; }
            public String description() { return "name"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<WebRouteContribution> routes() { return List.of(WebRouteContribution.publicRoute("/operation-proof/**")); }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/web/", "/operation-proof/"));
            }
            public List<I18nContribution> i18n() { return List.of(new I18nContribution("operation-proof", "i18n.web.operation-proof")); }
        };
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    public static class Wiring {
        @Bean OperationVerification proof() {
            return new OperationVerification() {
                public String policyId() { return "fixture-proof"; }
                public String generation() { return generation; }
                public Policy policy() {
                    return new Policy(revision, "", INTERACTION, List.of(new PageResourceProfile("worker", "one",
                            List.of(new PageResourceProfile.Source(PageResourceProfile.Kind.WORKER, "self")))));
                }
                public OperationVerificationResult verify(OperationVerificationRequest request) {
                    if (mode.equals("fail")) throw new IllegalStateException("fixture-provider-failed");
                    if (!request.response()) {
                        issued.incrementAndGet();
                        return new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, request.attemptId());
                    }
                    verified.incrementAndGet();
                    return new OperationVerificationResult(request.proof().equals("fixture:" + request.attemptId())
                            ? OperationVerificationResult.Status.APPROVED : OperationVerificationResult.Status.REJECTED, "");
                }
            };
        }
        @Bean PageContribution pages() {
            return new PageContribution() {
                public void validate(String type, String configuration) {
                    if (!type.equals("operation-proof:verify")) throw new IllegalArgumentException("PAGE_COMPONENT_UNAVAILABLE");
                }
                public List<PageComponent> components() {
                    return List.of(new PageComponent("operation-proof:verify", 1, "name", "operation-proof",
                            "/operation-proof/proof.mjs", "", "{\"type\":\"object\",\"additionalProperties\":false}", "{}", true,
                            List.of(), List.of(), new PageInteraction.Participant("fixture-proof", OperationVerification.INTERACTION)));
                }
            };
        }
    }
}
