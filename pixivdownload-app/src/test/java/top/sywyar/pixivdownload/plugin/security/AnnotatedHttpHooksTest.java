package top.sywyar.pixivdownload.plugin.security;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.controller.*;
import top.sywyar.pixivdownload.plugin.web.page.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("插件方法声明经管理员批准后进入同一 HTTP Hook 管线")
class AnnotatedHttpHooksTest {
    @TempDir java.nio.file.Path root;
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    final HttpHookDeclarations declarations = new HttpHookDeclarations();
    final OperationHookCapabilityAdapter adapter = new OperationHookCapabilityAdapter(registry, declarations);
    final ExecutorService executor = Executors.newFixedThreadPool(4);
    final List<OperationHook.Invocation> events = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final OperationAdmissions admissions = new OperationAdmissions();
    AnnotationConfigApplicationContext child;
    ExternalCapabilityPublication publication;
    WebPublicationStore store;
    MockMvc http;
    boolean allow, failObserver, revised, extras;
    CountDownLatch observerEntered, observerRelease;

    @BeforeEach void start() {
        store = new WebPublicationStore(root.resolve("state"), root.resolve("enrolled"));
        var pages = new PageContributionRegistry(List.of(), registry);
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/annotation/**"), WebRouteContribution.admin("/api/web/operation-hooks/**")));
        var factory = new StaticListableBeanFactory();
        var endpoints = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
        endpoints.publications(store);
        var publications = new HookPublications(registry, endpoints, pages, store, admissions);
        var bindings = new DeclaredHttpHookBindings(declarations, endpoints, registry, store, publications);
        var identity = org.mockito.Mockito.mock(RequestOwnerIdentityResolver.class);
        org.mockito.Mockito.when(identity.isAdminAuthenticated(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                "yes".equals(((jakarta.servlet.http.HttpServletRequest) call.getArgument(0)).getHeader("X-Admin")));
        var messages = org.mockito.Mockito.mock(AppMessages.class);
        org.mockito.Mockito.when(messages.get(org.mockito.ArgumentMatchers.any(Locale.class), org.mockito.ArgumentMatchers.eq("hook-declaration.error")))
                .thenReturn("Refresh the declarations and try again.");
        var interception = new HttpOperationHooks(endpoints, store, registry, admissions, new HookChallenges(), publications, List.of(), executor);
        http = MockMvcBuilders.standaloneSetup(new PlainController(calls),
                new OperationHooksController(endpoints, registry, identity, publications, store),
                new DeclaredHttpHooksController(bindings, identity, messages)).addInterceptors(interception)
                .addFilters(new HookRequestFilter(factory.getBeanProvider(RequestMappingHandlerMapping.class),
                        factory.getBeanProvider(top.sywyar.pixivdownload.setup.CsrfProtectionFilter.class)))
                .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest()))).build();
        factory.addBean("mapping", http.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class));
        register(1);
    }
    void register(long generation) {
        child = new AnnotationConfigApplicationContext();
        if (revised) child.registerBean("gate", RevisedGate.class, RevisedGate::new);
        else child.registerBean("gate", Gate.class, Gate::new);
        if (extras) {
            child.getBeanFactory().registerSingleton("candidates", new Candidates());
            child.registerBean("contract", org.springframework.beans.factory.FactoryBean.class,
                    () -> new org.springframework.beans.factory.FactoryBean<OperationHook>() {
                        public OperationHook getObject() { return new ContractObserver(); }
                        public Class<?> getObjectType() { return OperationHook.class; }
                    });
        }
        var proxy = new ProxyFactory(new Observer()); proxy.setInterfaces(Observe.class);
        child.registerBean("observer", Observe.class, () -> (Observe) proxy.getProxy());
        child.refresh();
        var preparation = registry.allocatePreparation("unfamiliar", "unfamiliar", generation); registry.installPreparation(preparation);
        var prepared = adapter.prepare(preparation, child); publication = registry.publish(preparation); adapter.publish(prepared);
    }
    void withdraw() {
        adapter.withdraw(publication.owner());
        var drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        registry.retireDrained(drain); child.close();
    }
    @AfterEach void close() {
        if (observerRelease != null) observerRelease.countDown();
        if (child != null) child.close(); executor.shutdownNow();
    }
    DeclaredHttpHookBindings.Listing listing() throws Exception {
        var response = http.perform(get("/api/web/operation-hooks/declarations").param("provider", "unfamiliar").header("X-Admin", "yes"))
                .andExpect(status().isOk()).andReturn().getResponse();
        return HookRequestFilter.JSON.readValue(response.getContentAsByteArray(), DeclaredHttpHookBindings.Listing.class);
    }
    HookPublications.ConfigurationPlan plan(String hook, boolean remove) throws Exception {
        var list = listing(); var row = list.declarations().stream().filter(value -> value.hook().equals(hook)).findFirst().orElseThrow();
        var selection = new DeclaredHttpHookBindings.Selection(list.revision(), "unfamiliar", row.id(), row.fingerprint(), remove);
        var response = http.perform(post("/api/web/operation-hooks/declarations/prepare").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(selection))).andExpect(status().isOk()).andReturn().getResponse();
        return HookRequestFilter.JSON.readValue(response.getContentAsByteArray(), HookPublications.ConfigurationPlan.class);
    }
    void approve(String hook, boolean remove) throws Exception {
        var plan = plan(hook, remove);
        http.perform(post("/api/web/operation-hooks/publish").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.Publication(plan, plan.permissions())))).andExpect(status().isOk());
    }
    void publishSettings(WebPublicationStore.HookSettings settings) throws Exception {
        var response = http.perform(post("/api/web/operation-hooks/prepare").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.BindingConfiguration(store.snapshot().revision(), settings))))
                .andExpect(status().isOk()).andReturn().getResponse();
        var plan = HookRequestFilter.JSON.readValue(response.getContentAsByteArray(), HookPublications.ConfigurationPlan.class);
        http.perform(post("/api/web/operation-hooks/publish").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.Publication(plan, plan.permissions())))).andExpect(status().isOk());
    }
    @Test @DisplayName("原样控制器、代理回调、批准、缺席保护与同声明换代恢复")
    void publicationAndRestart() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OperationHookCalls.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
        http.perform(get("/api/web/operation-hooks/declarations").param("provider", "unfamiliar")).andExpect(status().isForbidden());
        assertThat(listing().declarations()).hasSize(2).allMatch(row -> row.status().equals("PENDING_APPROVAL"));
        http.perform(post("/annotation/save")).andExpect(status().isOk());
        assertThat(events).isEmpty(); calls.set(0);
        var plan = plan("declared-gate", false);
        http.perform(post("/api/web/operation-hooks/publish").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.Publication(plan, List.of())))).andExpect(status().isBadRequest());
        assertThat(store.snapshot().revision()).isZero();
        approve("declared-gate", false); approve("declared-observer", false);
        http.perform(post("/annotation/save")).andExpect(status().isForbidden());
        assertThat(calls).hasValue(0);
        assertThat(events).hasSize(2); assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.BLOCKED);
        allow = true; events.clear();
        http.perform(post("/annotation/alias")).andExpect(status().isOk());
        assertThat(calls).hasValue(1); assertThat(events).hasSize(2);
        failObserver = true;
        http.perform(post("/annotation/save")).andExpect(status().isOk());
        assertThat(calls).hasValue(2); failObserver = false;
        var stale = plan("declared-observer", true);
        withdraw();
        http.perform(post("/annotation/save")).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(2);
        assertThat(listing().declarations()).allMatch(row -> row.status().equals("UNAVAILABLE") && row.approved());
        // 缺席的其它 gate 保持原要求，撤销 observer 不需要让 gate 临时恢复。
        approve("declared-observer", true);
        register(2);
        assertThat(listing().declarations()).anyMatch(row -> row.hook().equals("declared-gate") && row.status().equals("ACTIVE"));
        http.perform(post("/annotation/save")).andExpect(status().isOk());
        http.perform(post("/api/web/operation-hooks/publish").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.Publication(stale, stale.permissions())))).andExpect(status().isBadRequest());
        approve("declared-gate", true);
        assertThat(store.snapshot().configuration().requirements()).isEmpty();
        admissions.closeAndDrain(listing().declarations().stream().map(DeclaredHttpHookBindings.View::endpoint)
                .collect(java.util.stream.Collectors.toSet()), java.time.Duration.ZERO);
        var messages = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(value -> value.contains("pluginId=unfamiliar") && value.contains("AnnotatedHttpHooksTest$Observer#completed")
                && value.contains("phase=AFTER_COMPLETION") && value.contains("POST /annotation/save"));
        assertThat(messages).noneMatch(value -> value.contains("private-secret"));
        if (Boolean.getBoolean("hooks.browser")) {
            var script = java.nio.file.Path.of(getClass().getResource("/page-components/declared-hooks-browser.mjs").toURI());
            var output = root.resolve("management-browser.log");
            var command = new ProcessBuilder("node", script.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
            command.environment().put("HOOK_STATIC_ROOT", java.nio.file.Path.of("src/main/resources/static").toAbsolutePath().toString());
            var browser = command.start();
            try {
                assertThat(browser.waitFor(120, TimeUnit.SECONDS)).isTrue();
                assertThat(browser.exitValue()).withFailMessage(java.nio.file.Files.readString(output)).isZero();
                System.out.print(java.nio.file.Files.readString(output));
            } finally { if (browser.isAlive()) browser.destroyForcibly(); }
        }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test @DisplayName("目录按完整条件消歧，复杂合同复用接口，声明变化不会随其它审批被隐式批准")
    void matchingAndChangedDeclaration() throws Exception {
        approve("declared-gate", false);
        withdraw(); extras = true; revised = true; register(2); allow = true;
        var list = listing().declarations();
        assertThat(list).anyMatch(row -> row.hook().equals("ambiguous") && row.status().equals("AMBIGUOUS"));
        assertThat(list).anyMatch(row -> row.hook().equals("absent") && row.status().equals("UNDECLARED"));
        assertThat(list).anyMatch(row -> row.hook().equals("declared-gate") && row.status().equals("CHANGED"));
        for (String hook : List.of("ambiguous", "absent")) {
            var listing = listing(); var row = listing.declarations().stream().filter(value -> value.hook().equals(hook)).findFirst().orElseThrow();
            http.perform(post("/api/web/operation-hooks/declarations/prepare").header("X-Admin", "yes").contentType("application/json")
                    .content(HookRequestFilter.JSON.writeValueAsBytes(new DeclaredHttpHookBindings.Selection(listing.revision(), "unfamiliar",
                            row.id(), row.fingerprint(), false)))).andExpect(status().isBadRequest());
        }
        approve("exact", false); approve("contract-observer", false);
        http.perform(post("/annotation/save")).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(0);
        var directory = http.perform(get("/api/web/operation-hooks/catalog").header("X-Admin", "yes"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var endpoints = Arrays.asList(HookRequestFilter.JSON.readValue(directory.getContentAsByteArray(), OperationTargets.Endpoint[].class));
        String first = endpoints.stream().filter(endpoint -> endpoint.handler().endsWith("#first")).findFirst().orElseThrow().id();
        String second = endpoints.stream().filter(endpoint -> endpoint.handler().endsWith("#second")).findFirst().orElseThrow().id();
        var approved = store.snapshot().configuration();
        for (boolean absent : List.of(false, true)) {
            if (absent) withdraw();
            for (var members : List.of(List.of(first), List.of(second), List.<String>of())) {
                publishSettings(new WebPublicationStore.HookSettings(members.isEmpty() ? Map.of() : Map.of("core:unrelated", members), approved.settings().bindings()));
                assertThat(store.snapshot().configuration().requirements()).isEqualTo(approved.requirements());
                assertThat(store.snapshot().configuration().permissions()).isEqualTo(approved.permissions());
                assertThat(listing().declarations()).anyMatch(row -> row.hook().equals("declared-gate")
                        && row.status().equals(absent ? "UNAVAILABLE" : "CHANGED"));
                events.clear();
                http.perform(post("/annotation/save")).andExpect(status().isServiceUnavailable());
                assertThat(calls).hasValue(0);
                assertThat(events).noneMatch(event -> event.phase() == OperationHook.Phase.BEFORE_INVOKE);
            }
        }
        register(3);
        approve("declared-gate", false);
        http.perform(post("/annotation/save")).andExpect(status().isOk());
        assertThat(events).anyMatch(event -> event.phase() == OperationHook.Phase.AFTER_COMPLETION && event.result().equals("{\"calls\":1}"));
        events.clear();
        http.perform(post("/annotation/conditional").param("mode", "b").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isOk());
        assertThat(events).isEmpty();
        http.perform(post("/annotation/conditional").param("mode", "a").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isOk());
        assertThat(events).hasSize(1);
        allow = false;
        var declaredBindings = store.snapshot().configuration().settings().bindings();
        var grouped = new ArrayList<>(declaredBindings);
        grouped.add(new OperationBinding("group-gate", "declared-gate", "core:chosen", "", OperationHook.Phase.BEFORE_INVOKE,
                0, OperationHook.Completion.ALL, "{}"));
        publishSettings(new WebPublicationStore.HookSettings(Map.of("core:chosen", List.of(first)), grouped));
        http.perform(post("/annotation/conditional").param("mode", "a").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isForbidden());
        var previousTarget = store.snapshot().configuration().requirements().stream().filter(value -> value.operation().equals("core:chosen")).findFirst().orElseThrow().targetContract();
        publishSettings(new WebPublicationStore.HookSettings(Map.of("core:chosen", List.of(second)), grouped));
        assertThat(store.snapshot().configuration().requirements()).anyMatch(value -> value.operation().equals("core:chosen") && !value.targetContract().equals(previousTarget));
        http.perform(post("/annotation/conditional").param("mode", "a").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isOk());
        http.perform(post("/annotation/conditional").param("mode", "b").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isForbidden());
        withdraw(); assertThat(declarations.snapshot()).isEmpty();
        var retained = store.snapshot().configuration().requirements();
        publishSettings(new WebPublicationStore.HookSettings(Map.of("core:chosen", List.of(second), "core:unrelated", List.of(first)), grouped));
        assertThat(store.snapshot().configuration().requirements()).isEqualTo(retained);
        long revision = store.snapshot().revision();
        http.perform(post("/api/web/operation-hooks/prepare").header("X-Admin", "yes").contentType("application/json")
                .content(HookRequestFilter.JSON.writeValueAsBytes(new OperationHooksController.BindingConfiguration(revision,
                        new WebPublicationStore.HookSettings(Map.of("core:chosen", List.of(first)), grouped)))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("HOOK_CONFIGURATION_UNAVAILABLE"));
        assertThat(store.snapshot().revision()).isEqualTo(revision);
        http.perform(post("/annotation/conditional").param("mode", "b").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isServiceUnavailable());
        publishSettings(new WebPublicationStore.HookSettings(Map.of(), declaredBindings));
        http.perform(post("/annotation/conditional").param("mode", "b").header("X-Version", "one").contentType("application/json")
                .accept("application/json").content("{}")).andExpect(status().isOk());
        admissions.closeAndDrain(Set.of(first, second, "core:chosen"), java.time.Duration.ZERO);
        admissions.reopen(Set.of(first, second, "core:chosen"));
    }
    @Test @DisplayName("注解回调撤回时保留真实在途租约，拒绝不能同步收尾的方法")
    void drainAndInvalidMethods() throws Exception {
        approve("declared-observer", false);
        observerEntered = new CountDownLatch(1); observerRelease = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var invocation = caller.submit(() -> { http.perform(post("/annotation/save")).andExpect(status().isOk()); return null; });
            assertThat(observerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            adapter.withdraw(publication.owner()); var drain = registry.withdraw(publication).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            assertThat(declarations.snapshot()).isEmpty();
            observerRelease.countDown(); invocation.get(5, TimeUnit.SECONDS);
            assertThat(drain.isDrained()).isTrue(); registry.retireDrained(drain);
        } finally { observerRelease.countDown(); caller.shutdownNow(); }
        for (Class<?> type : List.of(InvalidAsync.class, InvalidSignature.class)) {
            try (var invalid = new AnnotationConfigApplicationContext()) {
                invalid.registerBean(type); invalid.refresh();
                var preparation = registry.allocatePreparation("invalid", "invalid", 1); registry.installPreparation(preparation);
                try { assertThatThrownBy(() -> adapter.prepare(preparation, invalid)).isInstanceOf(IllegalArgumentException.class); }
                finally { assertThat(registry.discardUnpublished(preparation)).isTrue(); }
            }
        }
    }
    @RestController static class PlainController {
        private final AtomicInteger calls;
        PlainController(AtomicInteger calls) { this.calls = calls; }
        @PostMapping({"/annotation/save", "/annotation/alias"}) public Map<String, Object> save() { return Map.of("calls", calls.incrementAndGet()); }
        @PostMapping(path="/annotation/conditional", params="mode=a", headers="X-Version=one", consumes="application/json", produces="application/json")
        public Map<String, Object> first() { return save(); }
        @PostMapping(path="/annotation/conditional", params="mode=b", headers="X-Version=one", consumes="application/json", produces="application/json")
        public Map<String, Object> second() { return save(); }
    }
    class Gate {
        @HttpHook(id="declared-gate", targetOwner="core", method=HttpMethod.POST, path="/annotation/save", phase=OperationHook.Phase.BEFORE_INVOKE)
        public OperationHook.Decision check(OperationHook.Invocation invocation) {
            events.add(invocation);
            return new OperationHook.Decision(allow ? OperationHook.Verdict.CONTINUE : OperationHook.Verdict.REJECT, "");
        }
    }
    interface Observe { void completed(OperationHook.Invocation invocation); }
    class RevisedGate extends Gate {
        @Override @HttpHook(id="declared-gate", revision="2", targetOwner="core", method=HttpMethod.POST, path="/annotation/save", phase=OperationHook.Phase.BEFORE_INVOKE)
        public OperationHook.Decision check(OperationHook.Invocation invocation) { return super.check(invocation); }
    }
    class Candidates {
        @HttpHook(id="ambiguous", targetOwner="core", method=HttpMethod.POST, path="/annotation/conditional", phase=OperationHook.Phase.AFTER_COMPLETION)
        public void ambiguous(OperationHook.Invocation value) { events.add(value); }
        @HttpHook(id="absent", targetOwner="core", method=HttpMethod.GET, path="/annotation/missing", phase=OperationHook.Phase.AFTER_COMPLETION)
        public void absent(OperationHook.Invocation value) { events.add(value); }
        @HttpHook(id="exact", targetOwner="core", method=HttpMethod.POST, path="/annotation/conditional", params="mode=a",
                headers="X-Version=one", consumes="application/json", produces="application/json", phase=OperationHook.Phase.AFTER_COMPLETION)
        public void exact(OperationHook.Invocation value) { events.add(value); }
    }
    class ContractObserver implements OperationHook {
        public String id() { return "contract-observer"; }
        public String generation() { return "1"; }
        public Definition definition() {
            return new Definition("1", Set.of(Phase.AFTER_COMPLETION), OperationHooksTest.SCHEMA, "",
                    "{\"type\":\"object\",\"properties\":{\"calls\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1000}},\"required\":[\"calls\"],\"additionalProperties\":false}", null, List.of());
        }
        @HttpHook(id="contract-observer", targetOwner="core", method=HttpMethod.POST, path="/annotation/save", phase=Phase.AFTER_COMPLETION,
                fields=@HttpHook.Field(source=OperationBinding.Source.RESULT, path="/calls", name="calls"))
        public Decision invoke(Invocation value) { events.add(value); return Decision.proceed(); }
    }
    class Observer implements Observe {
        @HttpHook(id="declared-observer", targetOwner="core", method=HttpMethod.POST, path="/annotation/save", phase=OperationHook.Phase.AFTER_COMPLETION)
        public void completed(OperationHook.Invocation invocation) {
            events.add(invocation);
            if (observerEntered != null) {
                observerEntered.countDown();
                try { observerRelease.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            }
            if (failObserver) throw new IllegalStateException("private-secret");
        }
    }
    static class InvalidAsync {
        @org.springframework.scheduling.annotation.Async
        @HttpHook(id="invalid", targetOwner="core", method=HttpMethod.GET, path="/annotation/save", phase=OperationHook.Phase.AFTER_COMPLETION)
        public void callback(OperationHook.Invocation value) { }
    }
    static class InvalidSignature {
        @HttpHook(id="invalid", targetOwner="core", method=HttpMethod.GET, path="/annotation/save", phase=OperationHook.Phase.BEFORE_INVOKE)
        public String callback(OperationHook.Invocation value) { return "invalid"; }
    }
}
