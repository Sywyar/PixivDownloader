package acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
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
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();
    private static final java.util.Map<String, HttpResponse<String>> staged = new java.util.concurrent.ConcurrentHashMap<>();
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
        var catalog = JSON.readTree(request(origin, "/api/web/operation-hooks/catalog", null, cookie).body());
        var current = JSON.readTree(request(origin, "/api/web/operation-hooks/configuration", null, cookie).body());
        ObjectNode settings = ((ObjectNode) current.path("settings")).deepCopy();
        for (String operation : List.of("alpha", "beta")) {
            var members = ((ObjectNode) settings.path("groups")).putArray("operation-form:" + operation);
            for (var endpoint : catalog) for (var route : endpoint.path("routes"))
                if (route.path("method").asString().equals("POST") && (route.path("template").asString().equals("/api/operation-form/" + operation)
                        || operation.equals("alpha") && route.path("template").asString().equals("/api/operation-form/alpha-secondary")))
                    members.add(endpoint.path("id").asString());
        }
        var groupPlan = request(origin, "/api/web/operation-hooks/prepare", JSON.createObjectNode()
                .put("revision", current.path("revision").asLong()).set("settings", settings).toString(), cookie);
        check(groupPlan.statusCode() == 200, "unchanged controllers grouped through approved configuration");
        var preparedGroups = JSON.readTree(groupPlan.body());
        var groupPublish = JSON.createObjectNode().set("plan", preparedGroups);
        ((ObjectNode) groupPublish).set("approvals", preparedGroups.path("permissions"));
        check(request(origin, "/api/web/operation-hooks/publish", groupPublish.toString(), cookie).statusCode() == 200, "group configuration published");
        check(request(origin, "/api/operation-form/alpha", command("unprotected").toString(), cookie).statusCode() == 200, "unconfigured operation works");
        check(request(origin, "/api/operation-form/alpha", command("unprotected").toString(), null).statusCode() == 401, "owner still requires administrator authentication");
        browser(browser, origin, cookie, "configured-actions", fixed);
        browser(browser, origin, cookie, "operations", fixed);
        check(request(origin, "/api/web/page-publications/operation-form:page", null, null).statusCode() == 404,
                "anonymous projection cannot expose the private form");
        check(request(origin, "/api/web/page-publications/operation-form:page", null, cookie).statusCode() == 200,
                "administrator reads the formal private projection");
        int baseline = calls.get();
        var challenge = call(origin, cookie, "alpha", command("bound")); check(challenge.statusCode() == 409, "direct API requires proof");
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
        check(call(origin, cookie, "alpha", command("__fail")).statusCode() == 409, "business retry starts a fresh challenge");
        verifySharedPublications(context, origin, cookie, browser, fixed, proof);
        browser(browser, origin, cookie, "operation-hooks", fixed);
        verifyGenericHooks(origin, cookie, proof, calls);
        proof.getField("mode").set(null, "fail");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "provider exception fails closed");
        proof.getField("mode").set(null, "normal");
        var publication = context.getBean(WebPublicationStore.class).snapshot();
        coordinator.stop("page-editor");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 409, "editor absence does not remove operation requirements");
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

    static void verifyAccessForms(ConfigurableApplicationContext context, String origin, String cookie, String browser, Path fixed) throws Exception {
        var invites = context.getBean(top.sywyar.pixivdownload.setup.guest.GuestInviteService.class);
        var creation = new top.sywyar.pixivdownload.setup.guest.dto.InviteCreateRequest(); creation.setName("isolated-access");
        long id = invites.createInvite(creation); String code = invites.detail(id).code();
        java.nio.file.Files.writeString(fixed.resolve("access-fixture.json"), JSON.createObjectNode().put("code", code).toString());
        browser(browser, origin, cookie, "access", fixed);
        String token = JSON.readTree(fixed.resolve("access-session.json").toFile()).path("token").asString();
        check(invites.resolveSession(token).isPresent() && invites.resolveSession(code).isEmpty(), "only redeemed opaque credentials resolve");
        check(request(origin, "/api/download/status/active", null, "pixiv_invite_token=" + token).statusCode() == 200,
                "redeemed opaque credential enters the existing guest route");
        check(request(origin, "/api/download/status/active", null, "pixiv_invite_token=" + code).statusCode() == 403,
                "raw invite cookie cannot enter the same guest route");
        var direct = request(origin, "/api/auth/invite-redeem", JSON.createObjectNode().put("code", code).toString(), null);
        check(direct.statusCode() == 409 && direct.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("pixiv_invite_token=")), "direct invitation API cannot bypass proof");
        var login = request(origin, "/api/auth/login", "{\"username\":\"acceptance\",\"password\":\"Isolated-page-password-2026\"}", null);
        check(login.statusCode() == 409 && login.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("pixiv_session=")), "direct login API cannot bypass proof");
        invites.pause(id); check(invites.resolveSession(token).isEmpty(), "paused invitation invalidates issued credentials");
        invites.resume(id); check(invites.resolveSession(token).isPresent(), "resume preserves existing invitation semantics");
        var logout = request(origin, "/api/auth/logout", "{}", "pixiv_invite_token=" + token);
        check(logout.statusCode() == 200 && invites.resolveSession(token).isEmpty(), "logout revokes the server credential");
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        coordinator.stop("operation-proof");
        // 前面的真实浏览器提交共享登录限流窗口；缺席回归须实际到达 Hook 准入。
        Thread.sleep(60_000 - System.currentTimeMillis() % 60_000);
        var missing = request(origin, "/api/auth/invite-redeem", JSON.createObjectNode().put("code", code).toString(), null);
        check(missing.statusCode() == 503 && missing.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.startsWith("pixiv_invite_token=")), "provider absence preserves invite protection: " + missing.statusCode());
        coordinator.start("operation-proof");
        System.out.println("LOGIN_INVITE_REAL_HTTP_RAW_COOKIE_DIRECT_API_REVOCATION_AND_PROVIDER_ABSENCE_PASSED");
    }

    private static void verifyGenericHooks(String origin, String cookie, Class<?> provider, AtomicInteger calls) throws Exception {
        var targets = request(origin, "/api/web/operation-hooks/targets?kind=FORM_ACTION&id=operation-form:page&targetId=alpha", null, cookie);
        var resolved = JSON.readTree(targets.body());
        check(targets.statusCode() == 200 && resolved.path("endpoints").size() == 2, "one action resolves every declared endpoint");
        check(request(origin, "/api/web/operation-hooks/definitions", null, null).statusCode() == 401, "hook discovery requires administrator");
        check(call(origin, cookie, "alpha-alias", command("alias")).statusCode() == 409, "alias cannot bypass operation protection");
        check(call(origin, cookie, "alpha-secondary", command("alias")).statusCode() == 409, "second endpoint cannot bypass operation protection");
        var states = new java.util.LinkedHashMap<String, JsonNode>();
        for (String page : List.of("page", "other")) {
            var state = document(origin, cookie, page, null);
            check(java.util.stream.StreamSupport.stream(state.path("draft").path("hooks").spliterator(), false).filter(binding -> binding.path("hook").asString().equals("fixture-generic")).count() == 2, "real editor saved both generic phases");
            states.put(page, state);
        }
        var publication = JSON.createObjectNode().put("revision", states.get("page").path("revision").asLong()).put("restoring", false);
        publication.putArray("drafts").addObject().put("page", "operation-form:other").put("revision", states.get("other").path("revision").asLong());
        var plan = document(origin, cookie, "page/prepare", publication);
        publication.set("decision", JSON.createObjectNode().put("revision", plan.path("revision").asLong()).put("fingerprint", plan.path("fingerprint").asString())
                .set("approvals", plan.path("permissions")));
        document(origin, cookie, "page/publish", publication);
        @SuppressWarnings("unchecked") var events = (List<String>) provider.getField("hookEvents").get(null);
        events.clear(); provider.getField("hookMode").set(null, "reject");
        int baseline = calls.get();
        check(call(origin, cookie, "alpha", command("blocked")).statusCode() == 403, "generic reject takes precedence over challenge");
        check(events.equals(List.of("BEFORE_INVOKE:NONE", "AFTER_COMPLETION:BLOCKED")) && calls.get() == baseline,
                "blocked request observes no business execution");
        provider.getField("hookMode").set(null, "normal");
        var accepted = response(call(origin, cookie, "alpha-alias", command("saved")), "saved");
        events.clear();
        check(call(origin, cookie, "alpha-alias", accepted).statusCode() == 200 && calls.get() == baseline + 1, "same endpoint alias uses trusted identity");
        check(events.equals(List.of("AFTER_COMPLETION:BLOCKED", "BEFORE_INVOKE:NONE", "AFTER_COMPLETION:SUCCEEDED")), "unknown plugin receives before and successful completion");
        var failed = response(call(origin, cookie, "alpha", command("__fail")), "__fail"); events.clear();
        check(call(origin, cookie, "alpha", failed).statusCode() == 500, "business exception remains a failure");
        check(events.equals(List.of("AFTER_COMPLETION:BLOCKED", "BEFORE_INVOKE:NONE", "AFTER_COMPLETION:FAILED")), "unknown plugin observes business failure");
        var observed = response(call(origin, cookie, "alpha", command("observed")), "observed"); events.clear();
        provider.getField("hookMode").set(null, "after-fail");
        check(call(origin, cookie, "alpha", observed).statusCode() == 200 && calls.get() == baseline + 2, "auxiliary failure preserves committed action once");
        check(call(origin, cookie, "alpha", observed).statusCode() == 409 && calls.get() == baseline + 2, "auxiliary failure never replays business");
        provider.getField("hookMode").set(null, "normal");
        System.out.println("GENERIC_HOOKS_REAL_HTTP_MULTI_ENDPOINT_BEFORE_AFTER_BLOCKED_FAILURE_AND_NO_REPLAY_PASSED");
    }

    private static void verifySharedPublications(ConfigurableApplicationContext context, String origin, String cookie,
            String browser, Path fixed, Class<?> proof) throws Exception {
        var primary = document(origin, cookie, "page", null);
        ObjectNode source = ((ObjectNode) primary.path("published")).deepCopy();
        source.put("pageId", "operation-form:other").put("title", "First published title");
        var other = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", 0).set("document", source));
        publish(origin, cookie, "other", other);
        source.put("title", "Second published title");
        other = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", other.path("revision").asLong()).set("document", source));
        publish(origin, cookie, "other", other);
        source.put("title", "Unfinished draft");
        other = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", other.path("revision").asLong()).set("document", source));
        proof.getField("revision").set(null, "two");
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 503, "unpublished policy changes fail closed");
        var before = context.getBean(WebPublicationStore.class).snapshot();
        var request = JSON.createObjectNode().put("revision", primary.path("revision").asLong()).put("restoring", false);
        var plan = document(origin, cookie, "page/prepare", request);
        check(plan.path("pages").toString().equals("[\"operation-form:page\",\"operation-form:other\"]"), "editor plans both related pages");
        request.set("decision", JSON.createObjectNode().put("revision", plan.path("revision").asLong())
                .put("fingerprint", plan.path("fingerprint").asString()).set("approvals", plan.path("permissions")));
        proof.getField("revision").set(null, "three");
        check(request(origin, "/api/page-editor/documents/operation-form:page/publish", request.toString(), cookie).statusCode() == 422,
                "policy change invalidates confirmed plan");
        check(context.getBean(WebPublicationStore.class).snapshot().equals(before), "stale batch leaves every page unchanged");
        proof.getField("revision").set(null, "two");
        browser(browser, origin, cookie, "operation-policy-update", fixed);
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 409, "editor publication restores shared operation availability");
        var after = document(origin, cookie, "other", null);
        for (String field : List.of("draft", "published", "previous", "revision"))
            check(after.path(field).equals(other.path(field)), "related page preserves " + field);
        check(context.getBean(WebPublicationStore.class).snapshot().revision() == before.revision() + 1, "both pages commit in one revision");
        System.out.println("SHARED_OPERATION_EDITOR_POLICY_UPDATE_PRESERVES_DRAFT_AND_HISTORY_PASSED");
        var primaryBound = document(origin, cookie, "page", null).path("published").deepCopy();
        var otherBound = after.path("published").deepCopy();
        browser(browser, origin, cookie, "operation-draft-batch", fixed);
        check(call(origin, cookie, "alpha", command("unbound")).statusCode() == 200, "selected drafts remove shared protection atomically");
        var a = document(origin, cookie, "page", null);
        var b = document(origin, cookie, "other", null);
        check(a.path("previous").equals(primaryBound) && b.path("previous").equals(otherBound), "batch retains each recovery point");
        verifySharedRestoration(context, origin, cookie, browser, fixed, proof);
        a = document(origin, cookie, "page", null); b = document(origin, cookie, "other", null);
        a = document(origin, cookie, "page/save", JSON.createObjectNode().put("revision", a.path("revision").asLong()).set("document", primaryBound));
        b = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", b.path("revision").asLong()).set("document", otherBound));
        var batch = JSON.createObjectNode().put("revision", a.path("revision").asLong()).put("restoring", false);
        batch.putArray("drafts").addObject().put("page", "operation-form:other").put("revision", b.path("revision").asLong());
        var batchPlan = document(origin, cookie, "page/prepare", batch);
        check(!batchPlan.path("permissions").isEmpty(), "resource permissions are shown for the selected batch");
        batch.set("decision", JSON.createObjectNode().put("revision", batchPlan.path("revision").asLong())
                .put("fingerprint", batchPlan.path("fingerprint").asString()).set("approvals", JSON.createArrayNode()));
        var unbound = context.getBean(WebPublicationStore.class).snapshot();
        check(request(origin, "/api/page-editor/documents/operation-form:page/publish", batch.toString(), cookie).statusCode() == 422,
                "selected batch requires explicit resource approval");
        ((ObjectNode) batch.get("decision")).set("approvals", batchPlan.path("permissions"));
        proof.getField("revision").set(null, "three");
        check(request(origin, "/api/page-editor/documents/operation-form:page/publish", batch.toString(), cookie).statusCode() == 422,
                "selected batch rejects changed provider policy");
        proof.getField("revision").set(null, "two");
        b = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", b.path("revision").asLong()).set("document", otherBound));
        check(request(origin, "/api/page-editor/documents/operation-form:page/publish", batch.toString(), cookie).statusCode() == 409,
                "selected batch rejects a concurrent draft save even when content is unchanged");
        ((ObjectNode) batch.path("drafts").get(0)).put("revision", b.path("revision").asLong());
        check(request(origin, "/api/page-editor/documents/operation-form:page/publish", batch.toString(), cookie).statusCode() == 422,
                "updated draft revision requires a new confirmation");
        check(context.getBean(WebPublicationStore.class).snapshot().equals(unbound), "rejected batches have no partial publication");
        batchPlan = document(origin, cookie, "page/prepare", batch);
        batch.set("decision", JSON.createObjectNode().put("revision", batchPlan.path("revision").asLong())
                .put("fingerprint", batchPlan.path("fingerprint").asString()).set("approvals", batchPlan.path("permissions")));
        document(origin, cookie, "page/publish", batch);
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 409, "confirmed draft batch restores protection");
        System.out.println("SHARED_OPERATION_SELECTED_DRAFTS_PERMISSIONS_POLICY_CAS_AND_RECOVERY_PASSED");
        a = document(origin, cookie, "page", null);
        ObjectNode changedTitle = ((ObjectNode) a.path("draft")).deepCopy(); changedTitle.put("title", "Changed after batch");
        a = document(origin, cookie, "page/save", JSON.createObjectNode().put("revision", a.path("revision").asLong()).set("document", changedTitle));
        publish(origin, cookie, "page", a);
        var incompatible = JSON.createObjectNode().put("revision", a.path("revision").asLong()).put("restoring", true);
        incompatible.putArray("drafts").addObject().put("page", "operation-form:other")
                .put("revision", document(origin, cookie, "other", null).path("revision").asLong());
        var unchanged = context.getBean(WebPublicationStore.class).snapshot();
        check(request(origin, "/api/page-editor/documents/operation-form:page/prepare", incompatible.toString(), cookie).statusCode() == 422,
                "incompatible historical requirements still reject the complete restore");
        check(context.getBean(WebPublicationStore.class).snapshot().equals(unchanged), "incompatible restore has no partial writes");

        proof.getField("revision").set(null, "one");
        publish(origin, cookie, "page", document(origin, cookie, "page", null));
    }

    private static void verifySharedRestoration(ConfigurableApplicationContext context, String origin, String cookie,
            String browser, Path fixed, Class<?> proof) throws Exception {
        var a = document(origin, cookie, "page", null);
        var b = document(origin, cookie, "other", null);
        for (String page : List.of("page", "other")) {
            var state = document(origin, cookie, page, null);
            check(request(origin, "/api/page-editor/documents/operation-form:" + page + "/prepare", JSON.createObjectNode()
                    .put("revision", state.path("revision").asLong()).put("restoring", true).toString(), cookie).statusCode() == 422,
                    "single historical restore cannot contradict the other published page");
        }
        var batch = JSON.createObjectNode().put("revision", a.path("revision").asLong()).put("restoring", true);
        batch.putArray("drafts").addObject().put("page", "operation-form:other").put("revision", b.path("revision").asLong());
        var plan = document(origin, cookie, "page/prepare", batch);
        check(!plan.path("permissions").isEmpty(), "historical bindings require resource approval");
        batch.set("decision", JSON.createObjectNode().put("revision", plan.path("revision").asLong())
                .put("fingerprint", plan.path("fingerprint").asString()).set("approvals", JSON.createArrayNode()));
        var before = context.getBean(WebPublicationStore.class).snapshot();
        String endpoint = "/api/page-editor/documents/operation-form:page/restore";
        check(request(origin, endpoint, batch.toString(), cookie).statusCode() == 422, "history restore rejects missing permissions");
        ((ObjectNode) batch.get("decision")).set("approvals", plan.path("permissions"));
        proof.getField("revision").set(null, "three");
        check(request(origin, endpoint, batch.toString(), cookie).statusCode() == 422, "policy changes invalidate restore confirmation");
        proof.getField("revision").set(null, "two");
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        coordinator.stop("operation-proof"); coordinator.start("operation-proof");
        check(request(origin, endpoint, batch.toString(), cookie).statusCode() == 422, "catalog replacement invalidates restore confirmation");
        plan = document(origin, cookie, "page/prepare", batch);
        batch.set("decision", JSON.createObjectNode().put("revision", plan.path("revision").asLong())
                .put("fingerprint", plan.path("fingerprint").asString()).set("approvals", plan.path("permissions")));
        b = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", b.path("revision").asLong()).set("document", b.path("draft")));
        check(request(origin, endpoint, batch.toString(), cookie).statusCode() == 409, "draft changes invalidate restore confirmation");
        ((ObjectNode) batch.path("drafts").get(0)).put("revision", b.path("revision").asLong());
        check(request(origin, endpoint, batch.toString(), cookie).statusCode() == 422, "updated draft revision still needs a new restore confirmation");
        check(context.getBean(WebPublicationStore.class).snapshot().equals(before), "rejected history batches leave all publications unchanged");
        check(document(origin, cookie, "page", null).equals(a), "rejected history batches preserve the primary draft");
        browser(browser, origin, cookie, "operation-history-batch", fixed);
        check(call(origin, cookie, "alpha", command("bound")).statusCode() == 409, "real editor history restore reinstates verification");
        browser(browser, origin, cookie, "operation-history-batch", fixed);
        check(call(origin, cookie, "alpha", command("unbound")).statusCode() == 200, "the inverse historical restore also completes atomically");
        System.out.println("SHARED_OPERATION_HISTORY_RESTORE_REAL_EDITOR_PERMISSIONS_POLICY_CATALOG_CAS_AND_ROUND_TRIP_PASSED");
    }

    static void verifyPresentationWithdrawal(ConfigurableApplicationContext context, String origin, String cookie) throws Exception {
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var other = document(origin, cookie, "other", null);
        ObjectNode source = ((ObjectNode) other.path("published")).deepCopy();
        var root = (tools.jackson.databind.node.ArrayNode) source.path("project").path("pages").get(0)
                .path("frames").get(0).path("component").path("components");
        var decoration = root.addObject().put("type", "page-component").put("componentType", "page-checklist:list")
                .put("configVersion", 1).put("instanceId", "decoration");
        decoration.set("configuration", JSON.readTree("{\"title\":\"Decoration\",\"items\":[{\"label\":\"Item\",\"checked\":false}]}"));
        other = document(origin, cookie, "other/save", JSON.createObjectNode().put("revision", other.path("revision").asLong()).set("document", source));
        publish(origin, cookie, "other", other);
        var retained = document(origin, cookie, "other", null);
        coordinator.stop("page-checklist");
        try {
            check(call(origin, cookie, "alpha", command("bound")).statusCode() == 409, "ordinary decoration withdrawal does not affect operation availability");
            var primary = document(origin, cookie, "page", null);
            ObjectNode changed = ((ObjectNode) primary.path("draft")).deepCopy(); changed.put("title", "Title without changed requirements");
            primary = document(origin, cookie, "page/save", JSON.createObjectNode().put("revision", primary.path("revision").asLong()).set("document", changed));
            var plan = document(origin, cookie, "page/prepare", JSON.createObjectNode().put("revision", primary.path("revision").asLong()).put("restoring", false));
            check(plan.path("pages").toString().equals("[\"operation-form:page\"]"), "unchanged operation requirements do not recompile the unrelated page");
            publish(origin, cookie, "page", primary);
            var preserved = document(origin, cookie, "other", null);
            for (String field : List.of("draft", "published", "previous", "revision"))
                check(preserved.path(field).equals(retained.path(field)), "unavailable related page preserves " + field);
            check(request(origin, "/api/page-editor/documents/operation-form:other/prepare", JSON.createObjectNode()
                    .put("revision", preserved.path("revision").asLong()).put("restoring", false).toString(), cookie).statusCode() == 422,
                    "the unavailable component still prevents publishing its own page");
        } finally { coordinator.start("page-checklist"); }
        System.out.println("SHARED_OPERATION_UNRELATED_DECORATION_WITHDRAWAL_PASSED");
    }

    private static JsonNode document(String origin, String cookie, String path, ObjectNode body) throws Exception {
        var response = request(origin, "/api/page-editor/documents/operation-form:" + path, body == null ? null : body.toString(), cookie);
        check(response.statusCode() == 200, "editor document " + path + ": " + response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }

    private static void publish(String origin, String cookie, String page, JsonNode state) throws Exception {
        var command = JSON.createObjectNode().put("revision", state.path("revision").asLong()).put("restoring", false);
        var plan = document(origin, cookie, page + "/prepare", command);
        command.set("decision", JSON.createObjectNode().put("revision", plan.path("revision").asLong())
                .put("fingerprint", plan.path("fingerprint").asString()).set("approvals", plan.path("permissions")));
        document(origin, cookie, page + "/publish", command);
    }
    private static ObjectNode command(String value) {
        var result = JSON.createObjectNode().put("value", value).put("token", ""); result.putObject("proofs"); return result;
    }
    private static ObjectNode response(HttpResponse<String> challenge, String value) throws Exception {
        check(challenge.statusCode() == 409, "challenge required before response");
        JsonNode data = JSON.readTree(challenge.body());
        var result = command(value).put("token", data.path("token").asString());
        ((ObjectNode) result.get("proofs")).put("fixture-proof", "fixture:" + data.path("challenges").path("fixture-proof").asString());
        return result;
    }
    private static HttpResponse<String> call(String origin, String cookie, String target, ObjectNode command) throws Exception {
        String path = "/api/operation-form/" + target, token = command.path("token").asString();
        if (!token.isEmpty()) {
            var stagedResponse = staged.computeIfAbsent(token, ignored -> {
                var prepare = JSON.createObjectNode().put("method", "POST").put("url", path).put("token", token).put("nativeSubmit", false);
                prepare.putObject("headers").put("Content-Type", "application/json");
                prepare.set("proofs", command.path("proofs")); prepare.putObject("json").put("/value", command.path("value").asString());
                try { return hookRequest(origin, "/api/operation-hooks/prepare", prepare.toString(), cookie, ""); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
            if (stagedResponse.statusCode() != 200) return stagedResponse;
        }
        return hookRequest(origin, path, JSON.createObjectNode().put("value", command.path("value").asString()).toString(), cookie, token);
    }
    private static int uncheckedCall(String origin, String cookie, ObjectNode command) {
        try { return call(origin, cookie, "alpha", command).statusCode(); }
        catch (Exception failure) { throw new CompletionException(failure); }
    }
}
