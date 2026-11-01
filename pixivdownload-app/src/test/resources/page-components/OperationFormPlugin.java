package example.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.pf4j.*;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** 冻结 SDK 后独立编译的业务 owner；不引用宿主或验证插件实现。 */
public final class OperationFormPlugin extends Plugin implements PixivPluginProvider {
    public static final AtomicInteger calls = new AtomicInteger();
    public static final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"maxLength\":64}},\"required\":[\"value\"],\"additionalProperties\":false}";
    public OperationFormPlugin(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "operation-form"; }
            public String displayName() { return "name"; }
            public String description() { return "name"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<ServiceKey<?>> serviceImports() { return List.of(OperationVerification.IMPORT); }
            public List<WebRouteContribution> routes() {
                return List.of(WebRouteContribution.admin("/operation-form/**"), WebRouteContribution.admin("/api/operation-form/**"));
            }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/web/", "/operation-form/"));
            }
            public List<I18nContribution> i18n() { return List.of(new I18nContribution("operation-form", "i18n.web.operation-form")); }
        };
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    public static class Wiring {
        @Bean FormController controller(OperationGuard guard, RequestOwnerIdentityResolver identities) { return new FormController(guard, identities); }
        @Bean PageContribution pages() {
            return new PageContribution() {
                public void validate(String type, String configuration) { throw new IllegalArgumentException("PAGE_COMPONENT_UNAVAILABLE"); }
                public List<EditablePage> pages() {
                    return List.of(new EditablePage("operation-form:page", "name", "operation-form", "/operation-form/index.html", false,
                            List.of(new PageRegion("alpha", "alpha", "", true, false), new PageRegion("alpha-fields", "fields", "alpha", false, false),
                                    new PageRegion("beta", "beta", "", true, false), new PageRegion("beta-fields", "fields", "beta", false, false)),
                            "", "", List.of(), List.of(target("alpha", "alpha"), target("beta", "beta"))),
                            new EditablePage("operation-form:ambiguous", "ambiguous", "operation-form", "/operation-form/ambiguous.html", false,
                                    List.of(new PageRegion("shared", "fields", "", true, false)), "", "", List.of(),
                                    List.of(target("alpha", "shared"), target("beta", "shared"))));
                }
            };
        }
        private static PageInteraction.Target target(String id, String region) {
            return new PageInteraction.Target(id, id, "operation-form:" + id, region, SCHEMA, List.of(OperationVerification.INTERACTION), 1);
        }
    }
    @RestController
    public static final class FormController {
        private final OperationGuard guard;
        private final RequestOwnerIdentityResolver identities;
        private final ObjectMapper json = new ObjectMapper();
        FormController(OperationGuard guard, RequestOwnerIdentityResolver identities) { this.guard = guard; this.identities = identities; }
        public record Command(String value, String token, Map<String, String> proofs) { }
        @PostMapping("/api/operation-form/{target}")
        public ResponseEntity<?> submit(@PathVariable("target") String target, HttpServletRequest request) throws IOException {
            if (!Set.of("alpha", "beta").contains(target)) return ResponseEntity.notFound().build();
            if (!identities.isAdminAuthenticated(request)) return ResponseEntity.status(401).build();
            byte[] bytes = request.getInputStream().readNBytes(71681);
            if (bytes.length > 71680) return ResponseEntity.status(413).build();
            Command command = json.readValue(bytes, Command.class);
            if (command.value() == null || command.value().length() > 64) return ResponseEntity.badRequest().build();
            var result = guard.execute("operation-form:" + target, "authenticated-administrator",
                    json.writeValueAsString(Map.of("value", command.value())), command.token(), command.proofs(), () -> {
                        if (command.value().equals("__fail")) throw new IOException("fixture-action-failed");
                        if (command.value().equals("__block")) {
                            entered.countDown();
                            boolean done = false;
                            while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                        }
                        return calls.incrementAndGet();
                    });
            return ResponseEntity.status(switch (result.status()) {
                case APPROVED -> 200; case CHALLENGE -> 428; case REJECTED -> 403;
                case UNAVAILABLE -> 503; case STALE -> 409; case INVALID -> 400;
            }).body(result);
        }
    }
}
