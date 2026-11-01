package acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static acceptance.PageEditorAcceptance.*;

/** 未知表单经真实 HTTP、签名安装和受管 child context 验证通用操作边界。 */
final class OperationFormsAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    static void verify(ConfigurableApplicationContext context, String origin, String cookie, String browser, Path fixed) throws Exception {
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var contexts = context.getBean(ExternalPluginContextManager.class);
        for (String owner : List.of("operation-form", "operation-proof")) {
            var artifact = fixed.resolve("fixture/" + owner + ".jar");
            var pending = coordinator.installOrUpdate(artifact, false, origin(artifact, false, null));
            var installed = coordinator.installOrUpdate(artifact, false,
                    origin(artifact, false, pending.installResult().trustRequirement().artifactSha256()));
            check(installed.activated(), "independent operation plugin activated: " + owner);
        }
        Class<?> form = contexts.contextFor("operation-form").orElseThrow().getClassLoader().loadClass("example.operation.OperationFormPlugin");
        Class<?> proof = contexts.contextFor("operation-proof").orElseThrow().getClassLoader().loadClass("example.operationproof.OperationProofPlugin");
        var calls = (AtomicInteger) form.getField("calls").get(null);
        check(request(origin, "/api/operation-form/alpha", command("unprotected").toString(), cookie).statusCode() == 200, "unconfigured operation works");
        check(request(origin, "/api/operation-form/alpha", command("unprotected").toString(), null).statusCode() == 401, "owner still requires administrator authentication");
        browser(browser, origin, cookie, "operations", fixed);
        check(request(origin, "/api/web/page-publications/operation-form:page", null, null).statusCode() == 404,
                "anonymous projection cannot expose the private form");
        check(request(origin, "/api/web/page-publications/operation-form:page", null, cookie).statusCode() == 200,
                "administrator reads the formal private projection");
        check(((AtomicInteger) proof.getField("issued").get(null)).get() == 3, "draft preview and publication did not issue provider challenges");
        int baseline = calls.get();
        var challenge = call(origin, cookie, "alpha", command("bound")); check(challenge.statusCode() == 428, "direct API requires proof");
        var response = response(challenge, "bound");
        ((ObjectNode) response.get("proofs")).put("fixture-proof", "forged");
        check(call(origin, cookie, "alpha", response).statusCode() == 403 && calls.get() == baseline, "fake proof cannot execute action");
        response = response(call(origin, cookie, "alpha", command("bound")), "changed");
        check(call(origin, cookie, "alpha", response).statusCode() == 409, "proof bound to business input");
        response = response(call(origin, cookie, "alpha", command("bound")), "bound");
        check(call(origin, cookie, "beta", response).statusCode() == 409, "proof cannot move between operations");
        final ObjectNode replay = response(call(origin, cookie, "alpha", command("bound")), "bound");
        var first = CompletableFuture.supplyAsync(() -> uncheckedCall(origin, cookie, replay));
        var second = CompletableFuture.supplyAsync(() -> uncheckedCall(origin, cookie, replay));
        check(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).stream().sorted().toList().equals(List.of(200, 409))
                && calls.get() == baseline + 1, "concurrent proof consumption executes exactly once");
        response = response(call(origin, cookie, "alpha", command("bound")), "bound");
        proof.getField("generation").set(null, "two");
        check(call(origin, cookie, "alpha", response).statusCode() == 409, "credential generation invalidates old proof");
        response = response(call(origin, cookie, "alpha", command("bound")), "bound");
        check(call(origin, cookie, "alpha", response).statusCode() == 200, "credential rotation preserves approved rules");
        var failedAction = response(call(origin, cookie, "alpha", command("__fail")), "__fail");
        check(call(origin, cookie, "alpha", failedAction).statusCode() == 500, "business action failure is not approved");
        check(call(origin, cookie, "alpha", failedAction).statusCode() == 409, "failed business action cannot reuse consumed proof");
        check(call(origin, cookie, "alpha", command("__fail")).statusCode() == 428, "business retry starts a fresh challenge");
        proof.getField("revision").set(null, "two");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "unpublished policy changes fail closed");
        proof.getField("revision").set(null, "one");
        proof.getField("mode").set(null, "fail");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "provider exception fails closed");
        proof.getField("mode").set(null, "normal");
        var publication = context.getBean(WebPublicationStore.class).snapshot();
        coordinator.stop("page-editor");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 428, "editor absence does not remove operation requirements");
        check(context.getBean(WebPublicationStore.class).snapshot().equals(publication), "editor stop preserves complete formal revision");
        coordinator.start("page-editor");
        var stale = response(call(origin, cookie, "alpha", command("bound")), "bound");
        final ObjectNode blocked = response(call(origin, cookie, "alpha", command("__block")), "__block");
        var inFlight = CompletableFuture.supplyAsync(() -> uncheckedCall(origin, cookie, blocked));
        var entered = (CountDownLatch) form.getField("entered").get(null);
        var release = (CountDownLatch) form.getField("release").get(null);
        var providerContext = contexts.contextFor("operation-proof").orElseThrow();
        try {
            check(entered.await(5, TimeUnit.SECONDS), "business action entered after verification");
            try { coordinator.stop("operation-proof"); }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) { }
            check(providerContext.isActive(), "provider context remains alive throughout owner action");
            check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "quiescing provider rejects new actions");
        } finally { release.countDown(); }
        check(inFlight.get(15, TimeUnit.SECONDS) == 200, "accepted action completes without replay");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (providerContext.isActive()) {
            try { coordinator.stop("operation-proof"); }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) {
                if (System.nanoTime() > deadline) throw pending;
                Thread.sleep(10);
            }
        }
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "missing provider retains the mandatory requirement");
        coordinator.start("operation-proof");
        check(call(origin, cookie, "alpha", stale).statusCode() == 409, "replacement rejects old challenge");
        System.out.println("OPERATION_FORMS_REAL_HTTP_BROWSER_REPLAY_GENERATION_EDITOR_ABSENCE_AND_ACTION_DRAIN_PASSED");
    }
    private static ObjectNode command(String value) {
        var result = JSON.createObjectNode().put("value", value).put("token", ""); result.putObject("proofs"); return result;
    }
    private static ObjectNode response(HttpResponse<String> challenge, String value) throws Exception {
        check(challenge.statusCode() == 428, "challenge required before response");
        JsonNode data = JSON.readTree(challenge.body());
        var result = command(value).put("token", data.path("token").asText());
        ((ObjectNode) result.get("proofs")).put("fixture-proof", "fixture:" + data.path("challenges").path("fixture-proof").asText());
        return result;
    }
    private static HttpResponse<String> call(String origin, String cookie, String target, ObjectNode command) throws Exception {
        return request(origin, "/api/operation-form/" + target, command.toString(), cookie);
    }
    private static int uncheckedCall(String origin, String cookie, ObjectNode command) {
        try { return call(origin, cookie, "alpha", command).statusCode(); }
        catch (Exception failure) { throw new CompletionException(failure); }
    }
}
