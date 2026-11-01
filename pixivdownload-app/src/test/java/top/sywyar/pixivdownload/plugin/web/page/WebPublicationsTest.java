package top.sywyar.pixivdownload.plugin.web.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.security.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.security.OperationGuard.Status.*;

@DisplayName("未知表单经正式发布保护真实操作，声明变更保持拒绝")
class WebPublicationsTest {
    @TempDir Path root;
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    final OperationAdmissions admissions = new OperationAdmissions();
    final AtomicInteger verifications = new AtomicInteger();
    final AtomicInteger actions = new AtomicInteger();
    final AtomicReference<String> revision = new AtomicReference<>("rules1");
    final AtomicReference<String> generation = new AtomicReference<>("credentials1");
    final String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
    final PageResourceProfile profile = new PageResourceProfile("worker", "r1", List.of(
            new PageResourceProfile.Source(PageResourceProfile.Kind.WORKER, "self")));

    record Fixture(WebPublications publications, WebPublicationStore store, OperationGuard guard, PageContributionRegistry catalog) { }
    Fixture fixture() { return fixture(List.of()); }
    Fixture fixture(List<OperationRequirement> required) {
        var store = new WebPublicationStore(root.resolve("data"), root.resolve("config/enrolled"));
        var catalog = new PageContributionRegistry(List.of(), registry);
        var form = registry.allocatePreparation("form", "forms", 1); registry.installPreparation(form);
        var services = registry.servicesFor(form, List.of(OperationVerification.IMPORT));
        var page = new EditablePage("form:page", "page", "form", "/form.html", true,
                List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(),
                List.of(new PageInteraction.Target("submit", "submit", "form:submit", "form", schema,
                        List.of(OperationVerification.INTERACTION), 1)));
        var otherPage = new EditablePage("form:other-page", page.labelKey(), page.namespace(), "/other.html", true,
                page.regions(), "", "", List.of(), page.targets());
        catalog.publish(new PageContributionRegistry.Entry(form.owner(), "form-token", List.of(), List.of(page, otherPage), List.of(), null));
        registry.publish(form);
        var provider = registry.allocatePreparation("proof", "proof", 1); registry.installPreparation(provider);
        registry.prepareService(provider, OperationVerificationCapabilityAdapter.key("neutral"), new OperationVerification() {
            public String policyId() { return "neutral"; }
            public String generation() { return generation.get(); }
            public Policy policy() { return new Policy(revision.get(), "", INTERACTION, List.of(profile)); }
            public OperationVerificationResult verify(OperationVerificationRequest request) {
                verifications.incrementAndGet();
                return new OperationVerificationResult(request.response() && request.proof().equals("answer")
                        ? OperationVerificationResult.Status.APPROVED : OperationVerificationResult.Status.CHALLENGE, "question");
            }
        });
        var port = new PagePort("value", "test-text", "{\"type\":\"string\"}");
        var component = new PageComponent("proof:widget", 1, "widget", "proof", "/widget.mjs", "", schema, "{}", true,
                List.of(port), List.of(port), new PageInteraction.Participant("neutral", OperationVerification.INTERACTION));
        catalog.publish(new PageContributionRegistry.Entry(provider.owner(), "proof-token", List.of(component), List.of(), List.of(), null));
        registry.publish(provider);
        var publications = new WebPublications(store, catalog, registry, admissions, required);
        var gate = new OperationVerificationGate(registry, OperationRequirement.collect(required), Clock.systemUTC(), store, catalog, admissions);
        return new Fixture(publications, store, gate.create(services), catalog);
    }

    PagePublications.Change change() {
        String view = """
                {"format":1,"pageId":"form:page","title":"Form","body":{"type":"wrapper","children":[
                {"type":"page-region","region":"form","children":[{"type":"page-component",
                "componentType":"proof:widget","instanceId":"one","configVersion":1,"configuration":{},"target":"submit"}]}]}}
                """;
        return new PagePublications.Change("form:page", "{\"format\":1}", view);
    }

    @Test @DisplayName("相同组件版本的模块或契约变化不会套用旧发布，页面 owner 声明变化拒绝读取")
    void rejectsChangedDeclarationsAtSameVersion() throws Exception {
        var fixture = fixture();
        var plan = fixture.publications().prepare(0, List.of(change()));
        fixture.publications().publish(plan, plan.permissions());
        var catalog = fixture.catalog();
        var component = catalog.components().get(0).definition();
        var owner = catalog.owners(Set.of("proof-token")).get(0);
        catalog.withdraw(owner);
        var changed = new PageComponent(component.type(), component.version(), component.labelKey(), component.namespace(),
                "/replacement.mjs", component.propertiesModuleUrl(), component.schema(), component.defaults(), component.publicRenderable(),
                component.inputs(), component.outputs(), component.participation());
        catalog.publish(new PageContributionRegistry.Entry(owner, "replacement-token", List.of(changed), List.of(), List.of(), null));
        assertThatThrownBy(() -> fixture.publications().render("form:page", fixture.store().snapshot())).hasMessage("PAGE_COMPONENT_UNAVAILABLE");
        assertThat(fixture.publications().read("form:page")).isPresent();
        var formOwner = catalog.owners(Set.of("form-token")).get(0);
        var page = catalog.pages().stream().filter(value -> value.definition().id().equals("form:page")).findFirst().orElseThrow().definition();
        catalog.withdraw(formOwner);
        catalog.publish(new PageContributionRegistry.Entry(formOwner, "form-next", List.of(), List.of(new EditablePage(
                page.id(), page.labelKey(), page.namespace(), page.path(), page.publicPage(), page.regions(), page.defaultProject(),
                page.previewModuleUrl(), page.stylesheets(), List.of())), List.of(), null));
        assertThatThrownBy(() -> fixture.publications().render("form:page", fixture.store().snapshot())).hasMessage("PAGE_OWNER_CHANGED");
        assertThat(fixture.publications().read("form:page")).isPresent();
        assertThat(fixture.guard().execute("form:submit", "user", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
    }

    PagePublications.Change withoutComponent(String page) throws Exception {
        var view = WebPublicationStore.JSON.readTree(change().projection());
        ((com.fasterxml.jackson.databind.node.ObjectNode) view).put("pageId", page);
        ((com.fasterxml.jackson.databind.node.ObjectNode) view.path("body").path("children").get(0)).putArray("children");
        return new PagePublications.Change(page, change().document(), view.toString());
    }

    @Test @DisplayName("固定保护要求及其资源权限不因恢复默认布局而消失")
    void staticProtectionSurvivesRemovingPageParticipant() throws Exception {
        var fixture = fixture(List.of(new OperationRequirement("form:submit", List.of("neutral"))));
        var plan = fixture.publications().prepare(0, List.of(withoutComponent("form:page")));
        assertThat(plan.permissions()).containsExactly(new PagePublications.Permission("proof", "neutral", profile));
        fixture.publications().publish(plan, plan.permissions());
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(CHALLENGE);
        revision.set("rules2");
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
        var updated = fixture.publications().prepare(1, List.of(withoutComponent("form:page")));
        fixture.publications().publish(updated, updated.permissions());
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(CHALLENGE);
    }

    @Test @DisplayName("同一操作在另一页面声明空保护也属于配置冲突")
    void rejectsEmptyProtectionConflictAcrossPages() throws Exception {
        var fixture = fixture();
        var first = fixture.publications().prepare(0, List.of(change()));
        fixture.publications().publish(first, first.permissions());
        var empty = withoutComponent("form:other-page");
        assertThatThrownBy(() -> fixture.publications().prepare(1, List.of(empty))).hasMessage("PAGE_OPERATION_CONFLICT");
        assertThat(fixture.store().snapshot().revision()).isEqualTo(1);
        var matching = new PagePublications.Change("form:other-page", change().document(),
                change().projection().replace("form:page", "form:other-page"));
        var plan = fixture.publications().prepare(1, List.of(matching));
        assertThat(plan.impacts().get(0).pages()).containsExactlyInAnyOrder("form:page", "form:other-page");
    }

    @Test @DisplayName("正式发布独立拒绝执行性布局、缺失区域、重复区域和普通端口环")
    void validatesProjectionWithoutEditor() throws Exception {
        var fixture = fixture();
        var original = WebPublicationStore.JSON.readTree(change().projection());
        var unsafe = original.deepCopy();
        var children = (com.fasterxml.jackson.databind.node.ArrayNode) unsafe.path("body").path("children");
        children.addObject().put("type", "page-box").put("role", "script");
        var unsafeChange = new PagePublications.Change(change().page(), change().document(), unsafe.toString());
        assertThatThrownBy(() -> fixture.publications().prepare(0, List.of(unsafeChange))).hasMessage("PAGE_NODE_INVALID");
        var missing = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.path("body").path("children")).removeAll();
        var missingChange = new PagePublications.Change(change().page(), change().document(), missing.toString());
        assertThatThrownBy(() -> fixture.publications().prepare(0, List.of(missingChange))).hasMessage("PAGE_REQUIRED_REGION");
        var duplicate = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("body").path("children")).add(original.path("body").path("children").get(0).deepCopy());
        var duplicateChange = new PagePublications.Change(change().page(), change().document(), duplicate.toString());
        assertThatThrownBy(() -> fixture.publications().prepare(0, List.of(duplicateChange))).hasMessage("PAGE_REGION_DUPLICATE");
        var cycle = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        cycle.putArray("connections").addObject().put("from", "one").put("to", "one").put("output", "value").put("input", "value");
        var cycleChange = new PagePublications.Change(change().page(), change().document(), cycle.toString());
        assertThatThrownBy(() -> fixture.publications().prepare(0, List.of(cycleChange))).hasMessage("PAGE_CONNECTION_CYCLE");
        assertThat(fixture.store().snapshot().revision()).isZero();
        assertThat(verifications).hasValue(0);
    }

    @Test @DisplayName("预检无验证副作用，资源未授权不提交，提交后直接调用也需单次证明")
    void publishesAndEnforcesUnknownOperation() throws Exception {
        var fixture = fixture();
        var plan = fixture.publications().prepare(0, List.of(change()));
        assertThat(verifications).hasValue(0);
        assertThat(fixture.store().snapshot().revision()).isZero();
        assertThat(plan.permissions()).containsExactly(new PagePublications.Permission("proof", "neutral", profile));
        assertThatThrownBy(() -> fixture.publications().publish(plan, List.of())).hasMessage("PAGE_RESOURCE_APPROVAL_REQUIRED");
        assertThat(fixture.publications().publish(plan, plan.permissions())).isEqualTo(1);
        var challenge = fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet);
        assertThat(challenge.status()).isEqualTo(CHALLENGE);
        assertThat(actions).hasValue(0);
        var response = fixture.guard().execute("form:submit", "account", "{}", challenge.token(), Map.of("neutral", "answer"), actions::incrementAndGet);
        assertThat(response.status()).isEqualTo(APPROVED);
        assertThat(actions).hasValue(1);
        assertThat(fixture.guard().execute("form:submit", "account", "{}", challenge.token(), Map.of("neutral", "answer"), actions::incrementAndGet).status())
                .isEqualTo(STALE);
        var projection = WebPublicationStore.JSON.readTree(fixture.publications().render("form:page", fixture.store().snapshot()).orElseThrow().projection());
        assertThat(projection.path("revision").asText()).isEqualTo(fixture.store().snapshot().identity());
        assertThat(projection.toString()).contains("proof-token");
    }

    @Test @DisplayName("发布封闭新操作并等实际业务动作结束，提交后拒绝旧证明")
    void publicationDrainsBusinessActionAndInvalidatesProofs() throws Exception {
        var fixture = fixture();
        var first = fixture.publications().prepare(0, List.of(change()));
        fixture.publications().publish(first, first.permissions());
        var oldProof = fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet);
        var runningProof = fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var running = executor.submit(() -> fixture.guard().execute("form:submit", "account", "{}", runningProof.token(),
                    Map.of("neutral", "answer"), () -> {
                        entered.countDown();
                        try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                        return actions.incrementAndGet();
                    }));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var replacement = fixture.publications().prepare(1, List.of(withoutComponent("form:page")));
            var publishing = executor.submit(() -> fixture.publications().publish(replacement, replacement.permissions()));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                    fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status() == UNAVAILABLE);
            assertThat(publishing.isDone()).isFalse();
            assertThat(fixture.store().snapshot().revision()).isEqualTo(1);
            assertThat(actions).hasValue(0);
            release.countDown();
            assertThat(running.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(APPROVED);
            assertThat(publishing.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(fixture.guard().execute("form:submit", "account", "{}", oldProof.token(), Map.of(), actions::incrementAndGet).status()).isEqualTo(STALE);
            assertThat(actions).hasValue(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test @DisplayName("策略变化必须重新发布，凭据轮换仅废弃旧挑战")
    void distinguishesRulesAndCredentials() throws Exception {
        var fixture = fixture();
        var plan = fixture.publications().prepare(0, List.of(change()));
        fixture.publications().publish(plan, plan.permissions());
        var first = fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet);
        generation.set("credentials2");
        assertThat(fixture.guard().execute("form:submit", "account", "{}", first.token(), Map.of("neutral", "answer"), actions::incrementAndGet).status())
                .isEqualTo(STALE);
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(CHALLENGE);
        revision.set("rules2");
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
        var updated = fixture.publications().prepare(1, List.of(change()));
        fixture.publications().publish(updated, updated.permissions());
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(CHALLENGE);
        assertThat(actions).hasValue(0);
    }

    @Test @DisplayName("预检后的配置变化、伪造绑定和发布记录缺失均不放行")
    void rejectsStalePlanForgedBindingAndMissingState() throws Exception {
        var fixture = fixture();
        var plan = fixture.publications().prepare(0, List.of(change()));
        revision.set("rules2");
        assertThatThrownBy(() -> fixture.publications().publish(plan, plan.permissions())).hasMessage("PAGE_PUBLICATION_STALE");
        var forged = new PagePublications.Change(change().page(), change().document(), change().projection().replace("\"target\":\"submit\"", "\"target\":\"other\""));
        assertThatThrownBy(() -> fixture.publications().prepare(0, List.of(forged))).hasMessage("PAGE_BINDING_INVALID");
        java.nio.file.Files.delete(root.resolve("data/current.json"));
        assertThat(fixture.guard().execute("form:submit", "account", "{}", "", Map.of(), actions::incrementAndGet).status()).isEqualTo(UNAVAILABLE);
        assertThat(actions).hasValue(0);
    }
}
