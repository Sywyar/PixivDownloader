package top.sywyar.pixivdownload.plugin.security;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.security.*;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationHookCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("真实 MVC 分发表中的通用前置后置回调与租约")
class OperationHooksTest {
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
    @TempDir java.nio.file.Path root;
    final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
    final List<OperationHook.Invocation> events = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicLong elapsed = new AtomicLong();
    OperationHook.Verdict verdict = OperationHook.Verdict.CONTINUE;
    boolean afterFailure;
    String inputSchema = "", resultSchema = "";
    boolean timeout;
    CountDownLatch entered, released;
    CountDownLatch afterEntered, afterReleased;
    WebPublicationStore store;
    OperationVerificationGate gate;
    OperationEndpoints endpoints;
    MockMvc mvc;
    ExternalCapabilityPublication provider;
    AnnotationConfigApplicationContext child;
    OperationHook hook;
    final ExecutorService hookExecutor = Executors.newFixedThreadPool(8);

    @BeforeEach void start() {
        store = new WebPublicationStore(root.resolve("publications"), root.resolve("config/enrolled"));
        var targets = List.of(new PageInteraction.Target("save", "save", "core:save", "form", SCHEMA, List.of(OperationVerification.INTERACTION), 16),
                new PageInteraction.Target("other", "other", "core:other", "form", SCHEMA, List.of(OperationVerification.INTERACTION), 16));
        var page = new EditablePage("core:form", "form", "test", "/form", false,
                List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(), targets);
        var pages = new PageContributionRegistry(List.of(page), registry);
        gate = new OperationVerificationGate(registry, Map.of(), Clock.systemUTC(), store, pages, new OperationAdmissions(), elapsed::get);
        var factory = new StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/hooks/**")));
        endpoints = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
        gate.endpoints(endpoints);
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, endpoints, pages, store, new OperationAdmissions());
        var interception = new HttpOperationHooks(endpoints, store, registry, new OperationAdmissions(), new HookChallenges(), contracts, List.of(), hookExecutor);
        mvc = MockMvcBuilders.standaloneSetup(new Controller(calls)).addInterceptors(interception)
                .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest())))
                .build();
        factory.addBean("mapping", mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class));
        hook = new OperationHook() {
            public String id() { return "unknown-callback"; }
            public String generation() { return "generation-one"; }
            public Definition definition() { return new Definition("rules-one", Set.of(Phase.BEFORE_INVOKE, Phase.AFTER_COMPLETION),
                    SCHEMA, inputSchema, resultSchema, OperationVerification.INTERACTION, List.of()); }
            public Decision invoke(Invocation invocation) {
                events.add(invocation);
                if (invocation.phase() == Phase.AFTER_COMPLETION && invocation.outcome() == Outcome.FAILED && afterEntered != null) {
                    afterEntered.countDown();
                    try { afterReleased.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("observer interrupted"); }
                }
                if (invocation.phase() == Phase.AFTER_COMPLETION && afterFailure) throw new IllegalStateException("private-fixture-error");
                if (invocation.phase() == Phase.BEFORE_INVOKE) {
                    if (entered != null) {
                        entered.countDown(); boolean done = false;
                        while (!done) try { released.await(); done = true; } catch (InterruptedException ignored) { }
                    }
                    if (timeout) elapsed.set(TimeUnit.SECONDS.toNanos(11));
                    return new Decision(invocation.response() && invocation.proof().equals("valid") ? Verdict.CONTINUE : verdict, "question");
                }
                return Decision.proceed();
            }
        };
        child = new AnnotationConfigApplicationContext(); child.registerBean(OperationHook.class, () -> hook); child.refresh();
        var preparation = registry.allocatePreparation("unknown-owner", "unknown-owner", 1); registry.installPreparation(preparation);
        new OperationHookCapabilityAdapter(registry, new HttpHookDeclarations()).prepare(preparation, child); provider = registry.publish(preparation);
        publish("");
    }

    @AfterEach void close() { if (released != null) released.countDown(); if (afterReleased != null) afterReleased.countDown(); hookExecutor.shutdownNow(); child.close(); }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"core", "operation-form"})
    @DisplayName("独立 JVM 重启保留候选身份，真实部署变化仍不能越过失效保护")
    void restartedTarget(String owner) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var classes = owner.equals("core")
                ? java.nio.file.Path.of(OperationEndpoints.class.getProtectionDomain().getCodeSource().getLocation().toURI()) : formClasses(classpath);
        var artifact = root.resolve("deployment.jar");
        for (String phase : List.of("seed", "restart", "changed")) {
            if (!phase.equals("restart")) {
                try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(artifact));
                        var files = java.nio.file.Files.walk(classes)) {
                    for (var file : files.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                        output.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                        java.nio.file.Files.copy(file, output); output.closeEntry();
                    }
                    output.putNextEntry(new java.util.jar.JarEntry("hooks-deployment.txt"));
                    output.write(phase.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
                }
            }
            var entries = Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                    .map(entry -> java.nio.file.Path.of(entry).equals(classes) ? artifact.toString() : entry).toList();
            if (owner.equals("core")) assertThat(entries).contains(artifact.toString());
            var arguments = root.resolve(phase + ".args"); var log = root.resolve(phase + ".log");
            java.nio.file.Files.writeString(arguments, "-cp\n" + argument(String.join(java.io.File.pathSeparator, entries))
                    + "\n" + RestartedTarget.class.getName() + "\n" + argument(root.toString()) + "\n" + phase + "\n" + owner,
                    java.nio.charset.StandardCharsets.UTF_8);
            var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(phase).isTrue();
                String output = java.nio.file.Files.readString(log, java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.exitValue()).withFailMessage(output).isZero();
                output.lines().filter(line -> line.startsWith("HOOK_RESTART_")).forEach(System.out::println);
            } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
        }
    }

    private static String argument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private java.nio.file.Path formClasses(String classpath) throws Exception {
        var source = root.resolve("OperationFormPlugin.java");
        try (var input = getClass().getResourceAsStream("/page-components/OperationFormPlugin.java")) {
            java.nio.file.Files.copy(Objects.requireNonNull(input), source);
        }
        var classes = java.nio.file.Files.createDirectory(root.resolve("form-classes"));
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-parameters",
                "--release", "17", "-classpath", classpath, "-d", classes.toString(), source.toString())).isZero();
        java.nio.file.Files.writeString(classes.resolve("plugin.properties"), """
                plugin.id=operation-form
                plugin.version=0.0.1
                plugin.requires=%s
                plugin.class=example.operation.OperationFormPlugin
                plugin.provider=fixture
                plugin.description=Operation form fixture
                pixiv.display-namespace=operation-form
                pixiv.display-name-key=name
                pixiv.description-key=name
                pixiv.kind=feature
                pixiv.configuration-classes=example.operation.OperationFormPlugin$Wiring
                pixiv.lifecycle-policy=hot-reload
                pixiv.execution-mode=host-process-full-trust
                """.formatted(top.sywyar.pixivdownload.sdk.SdkVersion.current()), java.nio.charset.StandardCharsets.UTF_8);
        return classes;
    }

    public static final class RestartedTarget {
        public static void main(String[] args) throws Exception {
            top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
            var fixture = new OperationHooksTest();
            fixture.root = java.nio.file.Path.of(args[0]).resolve("provider-" + args[1]);
            top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager runtime = null;
            try {
                fixture.start(); fixture.root = java.nio.file.Path.of(args[0]);
                if (!args[2].equals("core")) {
                    var plugins = java.nio.file.Files.createDirectory(fixture.root.resolve("plugins-" + args[1]));
                    var installed = java.nio.file.Files.copy(fixture.root.resolve("deployment.jar"), plugins.resolve("operation-form.jar"));
                    top.sywyar.pixivdownload.plugin.PluginTestProvenance.writeVerifiedLocalUpload(plugins, installed);
                    runtime = new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins,
                            top.sywyar.pixivdownload.plugin.PluginTestProvenance.verifier());
                    runtime.loadPlugin(installed); runtime.startPlugin(args[2]);
                    if (args[1].equals("seed")) { runtime.stopPlugin(args[2]); runtime.startPlugin(args[2]); }
                }
                fixture.restart(args[1], args[2], runtime);
            } finally {
                fixture.close();
                if (runtime != null) {
                    runtime.shutdown(); assertThat(runtime.deploymentIdentity(args[2], 1)).isEmpty();
                }
            }
        }
    }

    private void restart(String phase, String owner, top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager runtime) throws Exception {
        var publications = new WebPublicationStore(root.resolve("restart"), root.resolve("restart-enrolled"));
        var pages = new PageContributionRegistry(List.of(), registry);
        var factory = new StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/automatic/**")));
        var requests = new top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry();
        var requestOwner = runtime == null ? null : new top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestOwner(
                owner, runtime.generation(owner).orElseThrow(), phase.equals("restart") ? 1 : 2);
        if (requestOwner != null) {
            routes.register(requestOwner, List.of(WebRouteContribution.admin("/api/operation-form/**")));
            requests.publish(requestOwner);
        }
        var catalog = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
        catalog.publications(publications); catalog.requests(requests); catalog.runtime(runtime);
        var admissions = new OperationAdmissions();
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, catalog, pages, publications, admissions);
        var interception = new HttpOperationHooks(catalog, publications, registry, admissions, new HookChallenges(), contracts, List.of(), hookExecutor);
        Object controller = new PlainController(), other = new PlainController();
        var originalMethod = PlainController.class.getMethod("draft");
        var replacement = PlainController.class.getMethod("replacement", PlainController.Input.class);
        var siblingMethod = replacement;
        var businessCalls = ((PlainController) other).executions;
        if (runtime != null) {
            var loader = runtime.initializePlugin(owner).inventory().installations().get(0).classLoader();
            var type = loader.loadClass("example.operation.OperationFormPlugin$FormController");
            var command = loader.loadClass("example.operation.OperationFormPlugin$FormController$Command");
            controller = type.getConstructor().newInstance(); other = type.getConstructor().newInstance();
            originalMethod = type.getMethod("alpha", command); replacement = type.getMethod("beta", command);
            siblingMethod = type.getMethod("secondary", command);
            businessCalls = (AtomicInteger) loader.loadClass("example.operation.OperationFormPlugin").getField("calls").get(null);
        }
        var http = MockMvcBuilders.standaloneSetup(controller)
                .setCustomHandlerMapping(top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping::new)
                .addInterceptors(interception)
                .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest())))
                .build();
        var mapping = http.getDispatcherServlet().getWebApplicationContext().getBean(
                top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping.class);
        factory.addBean("mapping", mapping);
        String path = runtime == null ? "/automatic/restart" : "/api/operation-form/restart";
        var original = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths(path + "/{id}")
                .options(mapping.getBuilderConfiguration()).methods(RequestMethod.POST).params("mode=a").build();
        var sibling = original.mutate().paths(path + "/{key}").params("mode=a", "detail=yes").build();
        var renamed = original.mutate().paths(path + "/{value}").build();
        mapping.registerPluginMapping(owner, sibling, other, siblingMethod);
        boolean seed = phase.equals("seed");
        mapping.registerPluginMapping(owner, seed ? original : renamed, controller, seed ? originalMethod : replacement);
        java.util.function.Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> affected =
                () -> post(path + "/123").param("mode", "a").contentType("application/json").content("{\"value\":\"restart\"}");
        java.util.function.Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> unaffected =
                () -> affected.get().param("detail", "yes");
        verdict = OperationHook.Verdict.REJECT;
        if (seed) {
            String target = catalog.catalog(null).stream().filter(endpoint -> endpoint.routes().stream()
                    .anyMatch(route -> route.template().equals(path + "/{id}"))).findFirst().orElseThrow().id();
            var binding = new OperationBinding("gate", hook.id(), "", target, OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}");
            var plan = contracts.prepareConfiguration(0, new WebPublicationStore.HookSettings(Map.of(), List.of(binding)));
            contracts.publishConfiguration(plan, plan.permissions());
            http.perform(affected.get()).andExpect(status().isForbidden());
            http.perform(unaffected.get()).andExpect(status().isOk());
            mapping.unregisterMapping(original); mapping.registerPluginMapping(owner, renamed, controller, replacement);
        }
        var endpoint = catalog.catalog(null).stream().filter(value -> value.routes().stream()
                .anyMatch(route -> route.template().equals(path + "/{key}"))).findFirst().orElseThrow();
        String contract = top.sywyar.pixivdownload.plugin.web.page.HookPublications.targetContract(List.of(endpoint));
        var state = root.resolve("restart-state.json");
        if (seed) HookRequestFilter.JSON.writeValue(state.toFile(), Map.of("pid", ProcessHandle.current().pid(),
                "contract", contract, "publication", endpoint.publication(), "snapshot", publications.snapshot().identity()));
        else {
            var before = HookRequestFilter.JSON.readTree(state.toFile());
            assertThat(ProcessHandle.current().pid()).isNotEqualTo(before.path("pid").asLong());
            assertThat(contract).isEqualTo(before.path("contract").asText());
            if (runtime != null && phase.equals("changed")) assertThat(endpoint.publication()).isEqualTo(before.path("publication").asText());
            else assertThat(endpoint.publication()).isNotEqualTo(before.path("publication").asText());
            assertThat(publications.snapshot().identity()).isEqualTo(before.path("snapshot").asText());
        }
        events.clear();
        http.perform(affected.get()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_TARGET_STALE"));
        http.perform(unaffected.get()).andExpect(status().is(phase.equals("changed") ? 409 : 200));
        assertThat(businessCalls).hasValue(seed ? 2 : phase.equals("changed") ? 0 : 1);
        if (controller instanceof PlainController plain) assertThat(plain.executions).hasValue(0);
        assertThat(events).isEmpty();
        if (phase.equals("restart")) {
            for (boolean changedOwner : List.of(true, false)) {
                mapping.unregisterMapping(sibling);
                var changed = changedOwner ? sibling : sibling.mutate().headers("X-Changed=yes").build();
                mapping.registerPluginMapping(changedOwner ? "other-owner" : owner, changed, other, siblingMethod);
                http.perform(unaffected.get().header("X-Changed", "yes")).andExpect(status().isConflict());
                mapping.unregisterMapping(changed); mapping.registerPluginMapping(owner, sibling, other, siblingMethod);
            }
            http.perform(unaffected.get()).andExpect(status().isOk());
            assertThat(businessCalls).hasValue(2);
        }
        admissions.closeAndDrain(catalog.catalog(null).stream().map(OperationTargets.Endpoint::id).collect(java.util.stream.Collectors.toSet()), java.time.Duration.ZERO);
        if (requestOwner != null) {
            assertThat(requests.withdraw(requestOwner).orElseThrow().isDrained()).isTrue();
            assertThat(requests.retire(requestOwner)).isTrue();
        }
        assertThat(publications.snapshot().revision()).isEqualTo(1);
        System.out.println("HOOK_RESTART_PASSED owner=" + owner + " phase=" + phase + " pid=" + ProcessHandle.current().pid()
                + " affected=409 sibling=" + (phase.equals("changed") ? 409 : 200) + " unchangedContract=true revision=1");
    }

    @Test @DisplayName("路径变量与处理方法同时改名仍保留旧保护，显式重绑或撤销后才生效")
    void renamedTarget() throws Exception {
        var publications = new WebPublicationStore(root.resolve("renamed"), root.resolve("renamed-enrolled"));
        var pages = new PageContributionRegistry(List.of(), registry);
        var factory = new StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/automatic/**")));
        var catalog = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
        catalog.publications(publications);
        var admissions = new OperationAdmissions();
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, catalog, pages, publications, admissions);
        var interception = new HttpOperationHooks(catalog, publications, registry, admissions, new HookChallenges(), contracts, List.of(), hookExecutor);
        var controller = new PlainController();
        var http = MockMvcBuilders.standaloneSetup(controller)
                .setCustomHandlerMapping(top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping::new)
                .addInterceptors(interception)
                .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest())))
                .build();
        var mapping = http.getDispatcherServlet().getWebApplicationContext().getBean(
                top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping.class);
        factory.addBean("mapping", mapping);
        for (String[] scenario : List.of(new String[]{"item", "/automatic/items/123"},
                new String[]{"code", "/automatic/codes/AB12.json"}, new String[]{"files", "/automatic/files/a/b"})) {
            var original = mapping.getHandlerMethods().entrySet().stream()
                    .filter(entry -> entry.getValue().getMethod().getName().equals(scenario[0])).findFirst().orElseThrow();
            String oldPath = original.getKey().getPatternValues().iterator().next();
            var renamed = original.getKey().mutate().paths(oldPath.replace("{id", "{key").replace("{*path}", "{*remaining}"))
                    .build();
            String target = catalog.catalog(null).stream().filter(endpoint -> endpoint.handler().endsWith("#" + scenario[0])).findFirst().orElseThrow().id();
            var binding = new OperationBinding("gate", hook.id(), "", target, OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}");
            var plan = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of(binding)));
            contracts.publishConfiguration(plan, plan.permissions());
            verdict = OperationHook.Verdict.REJECT;
            http.perform(post(scenario[1])).andExpect(status().isForbidden());
            events.clear();
            int executed = controller.executions.get();
            mapping.unregisterMapping(original.getKey());
            try {
                mapping.registerMapping(renamed, controller, PlainController.class.getMethod("replacement", PlainController.Input.class));
                http.perform(post(scenario[1])).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_TARGET_STALE"));
                assertThat(controller.executions).hasValue(executed); assertThat(events).isEmpty();
                http.perform(post("/automatic/observe")).andExpect(status().isOk());
                http.perform(post("/automatic/conditional").param("mode", "draft")).andExpect(status().isOk());
                String replacement = catalog.catalog(null).stream().filter(endpoint -> endpoint.routes().stream()
                        .anyMatch(route -> route.template().equals(renamed.getPatternValues().iterator().next()))).findFirst().orElseThrow().id();
                var rebound = new OperationBinding("gate", hook.id(), "", replacement, OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}");
                plan = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of(rebound)));
                contracts.publishConfiguration(plan, plan.permissions());
                http.perform(post(scenario[1])).andExpect(status().isForbidden());
                verdict = OperationHook.Verdict.CONTINUE;
                http.perform(post(scenario[1])).andExpect(status().isOk());
                assertThat(controller.executions).hasValue(executed + 1);
                verdict = OperationHook.Verdict.REJECT;
                plan = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of()));
                contracts.publishConfiguration(plan, plan.permissions());
                http.perform(post(scenario[1])).andExpect(status().isOk());
                assertThat(controller.executions).hasValue(executed + 2);
                admissions.closeAndDrain(Set.of(target, replacement), java.time.Duration.ZERO);
                admissions.reopen(Set.of(target, replacement));
            } finally {
                mapping.unregisterMapping(renamed);
                mapping.registerMapping(original.getKey(), controller, original.getValue().getMethod());
            }
        }
        var base = mapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getMethod().getName().equals("draft")).findFirst().orElseThrow();
        for (String condition : List.of("params", "headers", "consumes", "produces", "absent-param", "absent-header", "optional-body",
                "overlap-params", "value-param", "overlap-headers", "value-header", "overlap-consumes", "overlap-produces", "overlap-method", "overlap-head")) {
            String path = "/automatic/scoped-" + condition;
            var first = base.getKey().mutate().paths(path + "/{id}").params(new String[0]);
            var second = base.getKey().mutate().paths(path + "/{key}").params(new String[0]);
            switch (condition) {
                case "params" -> { first.params("mode=a"); second.params("mode=b"); }
                case "headers" -> { first.headers("X-Mode=a"); second.headers("X-Mode=b"); }
                case "consumes" -> { first.consumes("application/json"); second.consumes("text/plain"); }
                case "produces" -> { first.produces("application/json"); second.produces("application/vnd.fixture+json"); }
                case "absent-param" -> { first.params("!mode"); second.params("mode"); }
                case "absent-header" -> { first.headers("!X-Mode"); second.headers("X-Mode"); }
                case "optional-body" -> { first.params("mode=a").consumes("application/json"); second.params("mode=b"); }
                case "overlap-params" -> { first.params("mode=a"); second.params("mode=a", "detail=yes"); }
                case "value-param" -> { first.params("mode"); second.params("mode=a"); }
                case "overlap-headers" -> { first.headers("X-Mode=a"); second.headers("X-Mode=a", "X-Detail=yes"); }
                case "value-header" -> { first.headers("X-Mode"); second.headers("X-Mode=a"); }
                case "overlap-consumes" -> { first.consumes("application/*"); second.consumes("application/json"); }
                case "overlap-produces" -> { first.produces("application/*"); second.produces("application/json"); }
                case "overlap-method" -> { first.methods(new RequestMethod[0]); second.methods(RequestMethod.POST); }
                case "overlap-head" -> { first.methods(RequestMethod.GET); second.methods(RequestMethod.HEAD); }
            }
            var original = first.build(); var sibling = second.build();
            if (condition.equals("optional-body")) original.getConsumesCondition().setBodyRequired(false);
            var renamed = original.mutate().paths(path + "/{value}").build();
            if (condition.equals("params")) renamed = renamed.mutate().headers("X-Extra=present").build();
            var other = new PlainController();
            var replacement = PlainController.class.getMethod("replacement", PlainController.Input.class);
            java.util.function.Function<Boolean, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> request = protectedRoute -> {
                var value = post(path + "/123");
                return switch (condition) {
                    case "params" -> value.param("mode", protectedRoute ? "a" : "b").header("X-Extra", "present");
                    case "headers" -> value.header("X-Mode", protectedRoute ? "a" : "b");
                    case "consumes" -> value.contentType(protectedRoute ? "application/json" : "text/plain").content("{}");
                    case "produces" -> value.accept(protectedRoute ? "application/json" : "application/vnd.fixture+json");
                    case "absent-param" -> protectedRoute ? value : value.param("mode", "b");
                    case "absent-header" -> protectedRoute ? value : value.header("X-Mode", "b");
                    case "overlap-params" -> protectedRoute ? value.param("mode", "a") : value.param("mode", "a").param("detail", "yes");
                    case "value-param" -> value.param("mode", protectedRoute ? "b" : "a");
                    case "overlap-headers" -> protectedRoute ? value.header("X-Mode", "a") : value.header("X-Mode", "a").header("X-Detail", "yes");
                    case "value-header" -> value.header("X-Mode", protectedRoute ? "b" : "a");
                    case "overlap-consumes" -> value.contentType(protectedRoute ? "application/xml" : "application/json").content("{}");
                    case "overlap-produces" -> value.accept(protectedRoute ? "application/vnd.fixture+json" : "application/json");
                    case "overlap-method" -> protectedRoute ? get(path + "/123") : value;
                    case "overlap-head" -> protectedRoute ? get(path + "/123") : head(path + "/123");
                    default -> value.param("mode", protectedRoute ? "a" : "b");
                };
            };
            mapping.registerMapping(original, controller, base.getValue().getMethod());
            mapping.registerMapping(sibling, other, replacement);
            try {
                var target = catalog.catalog(null).stream().filter(endpoint -> endpoint.routes().stream()
                        .anyMatch(route -> route.template().equals(path + "/{id}"))).findFirst().orElseThrow();
                var beforeSibling = catalog.catalog(null).stream().filter(endpoint -> endpoint.routes().stream()
                        .anyMatch(route -> route.template().equals(path + "/{key}"))).findFirst().orElseThrow();
                var binding = new OperationBinding("conditional", hook.id(), "", target.id(), OperationHook.Phase.BEFORE_INVOKE,
                        0, OperationHook.Completion.ALL, "{}");
                var plan = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of(binding)));
                contracts.publishConfiguration(plan, plan.permissions());
                assertThat(new WebPublicationStore(root.resolve("renamed"), root.resolve("renamed-enrolled")).snapshot().configuration())
                        .isEqualTo(publications.snapshot().configuration());
                verdict = OperationHook.Verdict.REJECT;
                http.perform(request.apply(true)).andExpect(status().isForbidden());
                http.perform(request.apply(false)).andExpect(status().isOk());
                assertThat(other.executions).hasValue(1);
                events.clear(); int executed = controller.executions.get();
                mapping.unregisterMapping(original);
                mapping.registerMapping(renamed, controller, replacement);
                assertThat(catalog.catalog(null)).contains(beforeSibling);
                http.perform(request.apply(true)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_TARGET_STALE"));
                http.perform(request.apply(false)).andExpect(status().isOk());
                assertThat(other.executions).as(condition).hasValue(2);
                assertThat(controller.executions).hasValue(executed); assertThat(events).isEmpty();
                mapping.unregisterMapping(sibling);
                mapping.registerPluginMapping("other-owner", sibling, other, replacement);
                boolean overlapping = condition.startsWith("overlap-") || condition.startsWith("value-");
                http.perform(request.apply(false)).andExpect(status().is(overlapping ? 409 : 200));
                assertThat(other.executions).hasValue(overlapping ? 2 : 3);
                if (overlapping) {
                    mapping.unregisterMapping(sibling);
                    mapping.registerMapping(sibling, other, replacement);
                    http.perform(request.apply(false)).andExpect(status().isOk());
                    assertThat(other.executions).hasValue(3);
                    var revoke = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of()));
                    contracts.publishConfiguration(revoke, revoke.permissions());
                    http.perform(request.apply(true)).andExpect(status().isOk());
                    assertThat(controller.executions).hasValue(executed + 1);
                }
                if (condition.equals("params")) {
                    var foreign = original.mutate().paths("/automatic/foreign/{id}").build();
                    mapping.registerPluginMapping("other-owner", foreign, other, base.getValue().getMethod());
                    try { http.perform(post("/automatic/foreign/123").param("mode", "a")).andExpect(status().isOk()); }
                    finally { mapping.unregisterMapping(foreign); }
                }
                admissions.closeAndDrain(Set.of(target.id()), java.time.Duration.ZERO); admissions.reopen(Set.of(target.id()));
            } finally {
                mapping.unregisterMapping(original); mapping.unregisterMapping(renamed); mapping.unregisterMapping(sibling);
            }
        }
    }

    @Test @DisplayName("配置发布可修改自身接口或分组的 Hook，仍排空其它请求及其后置回调")
    void selfPublication() throws Exception {
        var publications = new WebPublicationStore(root.resolve("self"), root.resolve("self-enrolled"));
        var pages = new PageContributionRegistry(List.of(), registry);
        var factory = new StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/api/web/operation-hooks/**")));
        var catalog = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
        catalog.publications(publications);
        var admissions = new OperationAdmissions();
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, catalog, pages, publications, admissions);
        var interception = new HttpOperationHooks(catalog, publications, registry, admissions, new HookChallenges(), contracts, List.of(), hookExecutor);
        var identities = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver.class);
        var held = new AtomicReference<CountDownLatch>(); var resume = new AtomicReference<CountDownLatch>();
        org.mockito.Mockito.when(identities.isAdminAuthenticated(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (jakarta.servlet.http.HttpServletRequest) call.getArgument(0);
            if (!"fixture".equals(request.getHeader("X-Test-Admin"))) return false;
            if ("yes".equals(request.getHeader("X-Hold"))) { held.get().countDown(); resume.get().await(); }
            return true;
        });
        var controller = new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController(catalog, registry, identities, contracts, publications);
        var http = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interception)
                .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest())))
                .build();
        factory.addBean("mapping", http.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class));
        var directory = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/web/operation-hooks/catalog")
                .header("X-Test-Admin", "fixture")).andExpect(status().isOk()).andReturn().getResponse();
        var discovered = Arrays.asList(HookRequestFilter.JSON.readValue(directory.getContentAsByteArray(), OperationTargets.Endpoint[].class));
        String target = discovered.stream().filter(endpoint -> endpoint.handler().endsWith("#publish")).findFirst().orElseThrow().id();
        String query = discovered.stream().filter(endpoint -> endpoint.handler().endsWith("#configuration")).findFirst().orElseThrow().id();
        var callers = Executors.newFixedThreadPool(2);
        try {
            for (boolean group : List.of(false, true)) for (int order : List.of(0, 1, 2, -1)) {
                long revision = publications.snapshot().revision();
                var bindings = new ArrayList<OperationBinding>();
                if (order >= 0) bindings.add(new OperationBinding("audit", hook.id(), group ? "core:management" : "", group ? "" : target,
                        OperationHook.Phase.AFTER_COMPLETION, order, OperationHook.Completion.ALL, "{}"));
                if (order == 2) bindings.add(new OperationBinding("gate", hook.id(), group ? "core:management" : "", group ? "" : target,
                        OperationHook.Phase.BEFORE_INVOKE, order, OperationHook.Completion.ALL, "{}"));
                var settings = new WebPublicationStore.HookSettings(group && order >= 0 ? Map.of("core:management", List.of(target, query)) : Map.of(),
                        bindings);
                var configuration = new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.BindingConfiguration(revision, settings);
                var preview = http.perform(post("/api/web/operation-hooks/prepare").header("X-Test-Admin", "fixture").contentType("application/json")
                        .content(HookRequestFilter.JSON.writeValueAsBytes(configuration))).andExpect(status().isOk()).andReturn().getResponse();
                var plan = HookRequestFilter.JSON.readValue(preview.getContentAsByteArray(), top.sywyar.pixivdownload.plugin.web.page.HookPublications.ConfigurationPlan.class);
                var value = new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.Publication(plan, plan.permissions());
                byte[] body = HookRequestFilter.JSON.writeValueAsBytes(value);
                if (order == 0) {
                    http.perform(post("/api/web/operation-hooks/publish").contentType("application/json").content(body)).andExpect(status().isForbidden());
                    http.perform(post("/api/web/operation-hooks/publish").header("X-Test-Admin", "fixture").contentType("application/json")
                            .content(HookRequestFilter.JSON.writeValueAsBytes(new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.Publication(plan, List.of()))))
                            .andExpect(status().isBadRequest());
                    assertThat(publications.snapshot().revision()).isEqualTo(revision);
                }
                if (order < 0) {
                    verdict = OperationHook.Verdict.REJECT;
                    http.perform(post("/api/web/operation-hooks/publish").header("X-Test-Admin", "fixture").contentType("application/json").content(body))
                            .andExpect(status().isForbidden());
                    assertThat(publications.snapshot().revision()).isEqualTo(revision);
                    verdict = OperationHook.Verdict.CONTINUE;
                }
                events.clear();
                if (order == 1) {
                    held.set(new CountDownLatch(1)); resume.set(new CountDownLatch(1));
                    afterEntered = new CountDownLatch(1); afterReleased = new CountDownLatch(1);
                    var other = callers.submit(() -> http.perform(post("/api/web/operation-hooks/publish").header("X-Test-Admin", "fixture")
                            .header("X-Hold", "yes").contentType("application/json").content(body)).andReturn());
                    assertThat(held.get().await(5, TimeUnit.SECONDS)).isTrue();
                    var publishing = callers.submit(() -> http.perform(post("/api/web/operation-hooks/publish").header("X-Test-Admin", "fixture")
                            .contentType("application/json").content(body)).andReturn());
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> {
                        try (var ignored = admissions.enter(target)) { return false; }
                        catch (IllegalStateException closed) { return closed.getMessage().equals("OPERATION_PUBLICATION_BUSY"); }
                    });
                    assertThatThrownBy(() -> publishing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    resume.get().countDown();
                    assertThat(afterEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> publishing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    assertThat(publications.snapshot().revision()).isEqualTo(revision);
                    afterReleased.countDown();
                    assertThat(other.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
                    assertThat(publishing.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
                    afterEntered = null; afterReleased = null;
                } else http.perform(post("/api/web/operation-hooks/publish").header("X-Test-Admin", "fixture").contentType("application/json").content(body))
                        .andExpect(status().isOk());
                assertThat(publications.snapshot().revision()).isEqualTo(revision + 1);
                assertThat(publications.snapshot().configuration().settings()).isEqualTo(settings);
                assertThat(events.stream().filter(event -> event.phase() == OperationHook.Phase.AFTER_COMPLETION && event.outcome() == OperationHook.Outcome.SUCCEEDED))
                        .hasSize(order == 0 ? 0 : 1);
                admissions.closeAndDrain(Set.of(target, query, "core:management"), java.time.Duration.ZERO);
                admissions.reopen(Set.of(target, query, "core:management"));
            }
        } finally {
            if (resume.get() != null) resume.get().countDown(); if (afterReleased != null) afterReleased.countDown();
            callers.shutdownNow(); assertThat(callers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private java.net.URLClassLoader editorLoader() throws Exception {
        // 编译当前插件源码，既不把可选插件加入宿主依赖，也不复用旧 target/classes。
        var source = java.nio.file.Path.of("../pixivdownload-plugin-page-editor/src/main/java").toAbsolutePath().normalize();
        var output = java.nio.file.Files.createDirectory(root.resolve("editor-classes"));
        var diagnostics = new java.io.ByteArrayOutputStream();
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-encoding", "UTF-8", "-parameters", "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-sourcepath", source.toString(), "-d", output.toString(),
                source.resolve("top/sywyar/pixivdownload/pageeditor/PageEditorController.java").toString());
        assertThat(compiled).withFailMessage(diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8)).isZero();
        return new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test @DisplayName("页面字段授权经真实编辑器批量预检、发布和恢复，过期确认不能写入")
    void pageFieldPermissions() throws Exception {
        try (var loader = editorLoader()) {
            var documentsType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageDocuments");
            var storeType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageStore");
            var controllerType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageEditorController");
            var libraryType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.components.ComponentLibrary");
            var declarations = List.of("core:a", "core:b").stream().map(id -> new EditablePage(id, "page", "test", "/" + id, true,
                    List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(),
                    List.of(new PageInteraction.Target("save", "save", "core:save", "form", SCHEMA,
                            List.of(OperationVerification.INTERACTION), 16)))).toList();
            var pages = new PageContributionRegistry(declarations, registry);
            var shared = new WebPublicationStore(root.resolve("field-pages"), root.resolve("field-enrolled"));
            var admissions = new OperationAdmissions();
            var publisher = new top.sywyar.pixivdownload.plugin.web.page.WebPublications(shared, pages, registry, admissions, List.of());
            publisher.hooks(new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, endpoints, pages, shared, admissions));
            var documents = documentsType.getConstructor(PageCatalog.class).newInstance(pages);
            var drafts = storeType.getConstructor(java.nio.file.Path.class, documentsType, PagePublications.class)
                    .newInstance(root.resolve("field-drafts"), documents, publisher);
            var messages = org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.MessageResolver.class);
            org.mockito.Mockito.when(messages.get("error.request.param.invalid")).thenReturn("Invalid request");
            var controller = controllerType.getConstructor(PageCatalog.class, storeType,
                    top.sywyar.pixivdownload.i18n.MessageResolver.class, libraryType)
                    .newInstance(pages, drafts, messages, null);
            var http = MockMvcBuilders.standaloneSetup(controller).build();
            inputSchema = "{\"type\":\"object\",\"properties\":{\"tag\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}";
            resultSchema = "{\"type\":\"object\",\"properties\":{\"display\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}";
            class Client {
                String base(String id) { return "/api/page-editor/documents/" + id; }
                com.fasterxml.jackson.databind.JsonNode read(String id) throws Exception {
                    return HookRequestFilter.JSON.readTree(http.perform(get(base(id))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                }
                com.fasterxml.jackson.databind.JsonNode send(String id, String action, com.fasterxml.jackson.databind.JsonNode body) throws Exception {
                    return HookRequestFilter.JSON.readTree(http.perform(post(base(id) + "/" + action).contentType("application/json").content(body.toString()))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                }
                void save(List<OperationBinding.Field> fields) throws Exception {
                    for (String id : List.of("core:a", "core:b")) {
                        var state = read(id);
                        var document = (com.fasterxml.jackson.databind.node.ObjectNode) state.path("draft");
                        document.putArray("hooks").add(HookRequestFilter.JSON.valueToTree(new OperationBinding("observer", hook.id(), "core:save", "",
                                OperationHook.Phase.AFTER_COMPLETION, 2, OperationHook.Completion.SUCCEEDED, "{}", fields)));
                        send(id, "save", HookRequestFilter.JSON.createObjectNode().put("revision", state.path("revision").asLong()).set("document", document));
                    }
                }
                com.fasterxml.jackson.databind.node.ObjectNode selection(boolean restoring) throws Exception {
                    var body = HookRequestFilter.JSON.createObjectNode().put("revision", read("core:a").path("revision").asLong()).put("restoring", restoring);
                    body.putArray("drafts").addObject().put("page", "core:b").put("revision", read("core:b").path("revision").asLong());
                    return body;
                }
                com.fasterxml.jackson.databind.node.ObjectNode decision(com.fasterxml.jackson.databind.node.ObjectNode selection,
                        com.fasterxml.jackson.databind.JsonNode preview) {
                    var command = selection.deepCopy();
                    command.putObject("decision").put("revision", preview.path("revision").asLong()).put("fingerprint", preview.path("fingerprint").asText())
                            .set("approvals", preview.path("permissions"));
                    return command;
                }
                void verify(com.fasterxml.jackson.databind.JsonNode preview, List<OperationBinding.Field> before, List<OperationBinding.Field> after) {
                    assertThat(preview.path("hooks")).hasSize(2);
                    for (var change : preview.path("hooks")) {
                        assertThat(change.path("page").asText()).isIn("core:a", "core:b");
                        for (String side : List.of("before", "after")) {
                            var access = change.path(side).get(0);
                            assertThat(access.path("owner").asText()).isEqualTo("unknown-owner");
                            assertThat(access.path("fields")).isEqualTo(HookRequestFilter.JSON.valueToTree(side.equals("before") ? before : after));
                            assertThat(access.path("completion").asText()).isEqualTo("SUCCEEDED");
                            assertThat(access.path("order").asInt()).isEqualTo(2);
                            assertThat(access.has("configuration")).isFalse();
                        }
                    }
                }
            }
            var client = new Client();
            client.save(List.of());
            var selection = client.selection(false);
            client.send("core:a", "publish", client.decision(selection, client.send("core:a", "prepare", selection)));
            var all = List.of(new OperationBinding.Field(OperationBinding.Source.QUERY, "tag", "tag"),
                    new OperationBinding.Field(OperationBinding.Source.JSON, "/email", "email"),
                    new OperationBinding.Field(OperationBinding.Source.RESULT, "/displayName", "display"));
            List<OperationBinding.Field> previous = List.of();
            for (var fields : List.of(all, List.of(new OperationBinding.Field(OperationBinding.Source.JSON, "/alias", "email")), List.<OperationBinding.Field>of())) {
                client.save(fields);
                selection = client.selection(false);
                var snapshot = shared.snapshot();
                var preview = client.send("core:a", "prepare", selection);
                client.verify(preview, previous, fields);
                assertThat(shared.snapshot()).isEqualTo(snapshot); // 预检后取消不写正式状态。
                var command = client.decision(selection, preview);
                var denied = command.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) denied.path("decision")).putArray("approvals");
                http.perform(post(client.base("core:a") + "/publish").contentType("application/json").content(denied.toString()))
                        .andExpect(status().isUnprocessableEntity());
                String originalSchema = inputSchema;
                inputSchema += " ";
                http.perform(post(client.base("core:a") + "/publish").contentType("application/json").content(command.toString()))
                        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAGE_PUBLICATION_STALE"));
                inputSchema = originalSchema;
                assertThat(shared.snapshot()).isEqualTo(snapshot);
                client.save(fields);
                http.perform(post(client.base("core:a") + "/publish").contentType("application/json").content(command.toString()))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAGE_REVISION_CONFLICT"));
                selection = client.selection(false);
                command = client.decision(selection, client.send("core:a", "prepare", selection));
                client.send("core:a", "publish", command);
                for (String id : List.of("core:a", "core:b")) {
                    assertThat(shared.snapshot().pages().get(id).hooks().get(0).binding().fields()).isEqualTo(fields);
                    assertThat(publisher.render(id, shared.snapshot()).orElseThrow().projection()).doesNotContain("/email", "/displayName", "/alias");
                }
                var restore = client.selection(true);
                var historical = client.send("core:a", "prepare", restore);
                client.verify(historical, fields, previous);
                client.send("core:a", "restore", client.decision(restore, historical));
                for (var page : shared.snapshot().pages().values()) assertThat(page.hooks().get(0).binding().fields()).isEqualTo(previous);
                restore = client.selection(true);
                client.send("core:a", "restore", client.decision(restore, client.send("core:a", "prepare", restore)));
                previous = fields;
            }
        } finally { inputSchema = ""; resultSchema = ""; }
    }

    @Test @DisplayName("真实页面编辑器自身发布恢复及可选输入观察保留业务正文和安全检查")
    void pageSelfPublication() throws Exception {
        try (var loader = editorLoader()) {
            var documentsType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageDocuments");
            var storeType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageStore");
            var controllerType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.PageEditorController");
            var libraryType = loader.loadClass("top.sywyar.pixivdownload.pageeditor.components.ComponentLibrary");
            for (boolean restoring : List.of(false, true)) {
                var publications = new WebPublicationStore(root.resolve("pages-" + restoring), root.resolve("enrolled-" + restoring));
                var page = new EditablePage("core:editor", "editor", "test", "/editor", true,
                        List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(), List.of());
                var pages = new PageContributionRegistry(List.of(page), registry);
                var factory = new StaticListableBeanFactory();
                var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
                routes.register("core", List.of(WebRouteContribution.admin("/api/page-editor/**")));
                var catalog = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pages);
                catalog.publications(publications);
                var admissions = new OperationAdmissions();
                var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, catalog, pages, publications, admissions);
                var publisher = new top.sywyar.pixivdownload.plugin.web.page.WebPublications(publications, pages, registry, admissions, List.of());
                publisher.hooks(contracts);
                var documents = documentsType.getConstructor(PageCatalog.class).newInstance(pages);
                var drafts = storeType.getConstructor(java.nio.file.Path.class, documentsType, PagePublications.class)
                        .newInstance(root.resolve("drafts-" + restoring), documents, publisher);
                var messages = org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.MessageResolver.class);
                org.mockito.Mockito.when(messages.get("error.request.param.invalid")).thenReturn("Invalid request");
                var controller = controllerType.getConstructor(PageCatalog.class, storeType,
                        top.sywyar.pixivdownload.i18n.MessageResolver.class, libraryType).newInstance(pages, drafts, messages, null);
                var interception = new HttpOperationHooks(catalog, publications, registry, admissions, new HookChallenges(), contracts, List.of(), hookExecutor);
                var held = new CountDownLatch(1); var resume = new CountDownLatch(1);
                var hold = new org.springframework.web.servlet.HandlerInterceptor() {
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                        if ("yes".equals(request.getHeader("X-Hold"))) { held.countDown(); resume.await(); }
                        return true;
                    }
                };
                var http = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interception, hold)
                        .addFilters((request, response, chain) -> chain.doFilter(
                                new top.sywyar.pixivdownload.common.web.BoundedBodyRequest((jakarta.servlet.http.HttpServletRequest) request), response))
                        .alwaysDo(result -> interception.requestDestroyed(new jakarta.servlet.ServletRequestEvent(result.getRequest().getServletContext(), result.getRequest())))
                        .build();
                factory.addBean("mapping", http.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class));
                String action = restoring ? "restore" : "publish";
                String endpoint = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#" + action)).findFirst().orElseThrow().id();
                class Client {
                    final String base = "/api/page-editor/documents/core:editor";
                    com.fasterxml.jackson.databind.node.ObjectNode get(String suffix) throws Exception {
                        var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + suffix))
                                .andExpect(status().isOk()).andReturn().getResponse();
                        return (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsByteArray());
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode send(String suffix, com.fasterxml.jackson.databind.JsonNode body) throws Exception {
                        var response = http.perform(post(base + suffix).contentType("application/json").content(body.toString()))
                                .andExpect(status().isOk()).andReturn().getResponse();
                        return (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsByteArray());
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode command(boolean restore) throws Exception {
                        long revision = get("").path("revision").asLong();
                        var preview = send("/prepare", HookRequestFilter.JSON.createObjectNode().put("revision", revision).put("restoring", restore));
                        var command = HookRequestFilter.JSON.createObjectNode().put("revision", revision);
                        command.putObject("decision").put("revision", preview.path("revision").asLong())
                                .put("fingerprint", preview.path("fingerprint").asText()).set("approvals", preview.path("permissions"));
                        return command;
                    }
                }
                var client = new Client();
                var document = client.get("/default");
                document.putArray("actions").addObject().put("id", "submit").put("formId", "form").put("actionId", "")
                        .put("region", "form").put("operation", "").put("endpoint", endpoint);
                document.putArray("hooks").add(HookRequestFilter.JSON.valueToTree(new OperationBinding("audit", hook.id(), "", endpoint,
                        OperationHook.Phase.AFTER_COMPLETION, 0, OperationHook.Completion.ALL, "{}")));
                client.send("/save", HookRequestFilter.JSON.createObjectNode().put("revision", 0).set("document", document));
                var initial = client.command(false);
                var denied = initial.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) denied.path("decision")).putArray("approvals");
                http.perform(post(client.base + "/publish").contentType("application/json").content(denied.toString())).andExpect(status().isUnprocessableEntity());
                assertThat(publications.snapshot().revision()).isZero();
                client.send("/publish", initial);
                assertThat(publications.snapshot().revision()).isEqualTo(1);
                document.put("title", "current");
                if (restoring) document.putArray("hooks");
                client.send("/save", HookRequestFilter.JSON.createObjectNode().put("revision", 1).set("document", document));
                if (restoring) client.send("/publish", client.command(false));
                var command = client.command(restoring);
                long before = publications.snapshot().revision();
                var callers = Executors.newFixedThreadPool(2);
                try {
                    // 另一个已准入的发布请求必须能失败退出，随后其观察回调仍须真实排空。
                    afterEntered = restoring ? null : new CountDownLatch(1); afterReleased = new CountDownLatch(1);
                    var other = callers.submit(() -> http.perform(post(client.base + "/" + action).header("X-Hold", "yes")
                            .contentType("application/json").content(command.toString())).andReturn());
                    assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                    var publishing = callers.submit(() -> client.send("/" + action, command));
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> {
                        try (var ignored = admissions.enter(endpoint)) { return false; }
                        catch (IllegalStateException closed) { return closed.getMessage().equals("OPERATION_PUBLICATION_BUSY"); }
                    });
                    assertThatThrownBy(() -> publishing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    http.perform(post(client.base + "/save").contentType("application/json").content(
                            HookRequestFilter.JSON.createObjectNode().put("revision", 2).set("document", document).toString())).andExpect(status().isConflict());
                    assertThat(publications.snapshot().revision()).isEqualTo(before);
                    resume.countDown();
                    if (!restoring) {
                        assertThat(afterEntered.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThatThrownBy(() -> publishing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                        assertThat(publications.snapshot().revision()).isEqualTo(before);
                    }
                    afterReleased.countDown();
                    assertThat(other.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
                    var committed = publishing.get(5, TimeUnit.SECONDS);
                    assertThat(committed.path("publicationRevision").asLong()).isEqualTo(before + 1);
                    assertThat(committed.path("published").path("title").asText()).isEqualTo(restoring ? "" : "current");
                    assertThat(client.get("").path("revision").asLong()).isEqualTo(restoring ? 3 : 2);
                    http.perform(post(client.base + "/" + action).contentType("application/json").content(command.toString()))
                            .andExpect(status().is4xxClientError());
                    assertThat(publications.snapshot().revision()).isEqualTo(before + 1);
                    admissions.closeAndDrain(Set.of(endpoint), java.time.Duration.ZERO); admissions.reopen(Set.of(endpoint));
                } finally {
                    resume.countDown(); afterReleased.countDown(); callers.shutdownNow();
                    assertThat(callers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                    afterEntered = null; afterReleased = null;
                }
                assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isNull();
                if (!restoring) {
                    String save = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#save")).findFirst().orElseThrow().id();
                    inputSchema = "{\"type\":\"object\",\"properties\":{\"revision\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"marker\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}";
                    var observer = new OperationBinding("json-observer", hook.id(), "", save, OperationHook.Phase.AFTER_COMPLETION,
                            0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.JSON, "/revision", "revision")));
                    var gateBinding = new OperationBinding("query-gate", hook.id(), "", save, OperationHook.Phase.BEFORE_INVOKE,
                            0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.QUERY, "marker", "marker")));
                    var queryObserver = new OperationBinding("query-observer", hook.id(), "", save, OperationHook.Phase.AFTER_COMPLETION,
                            1, OperationHook.Completion.ALL, "{}", gateBinding.fields());
                    class Save {
                        void configure(List<OperationBinding> bindings) throws Exception {
                            var plan = contracts.prepareConfiguration(publications.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), bindings));
                            contracts.publishConfiguration(plan, plan.permissions());
                        }
                        com.fasterxml.jackson.databind.node.ObjectNode body() throws Exception {
                            return HookRequestFilter.JSON.createObjectNode().put("revision", client.get("").path("revision").asLong()).set("document", document);
                        }
                        void succeeds(com.fasterxml.jackson.databind.node.ObjectNode body, int callbacks) throws Exception {
                            events.clear();
                            var saved = client.send("/save?marker=approved", body);
                            assertThat(saved.path("revision").asLong()).isEqualTo(body.path("revision").asLong() + 1);
                            assertThat(saved.path("draft")).isEqualTo(body.path("document"));
                            assertThat(events).hasSize(callbacks);
                        }
                    }
                    var saving = new Save();
                    saving.configure(List.of(observer)); saving.succeeds(saving.body(), 1);
                    ((com.fasterxml.jackson.databind.node.ArrayNode) document.at("/project/pages/0/frames/0/component/components/0/components"))
                            .addObject().put("type", "page-text").put("content", "review ".repeat(10000));
                    var large = saving.body();
                    assertThat(large.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isBetween(65537, 1048576);
                    saving.succeeds(large, 0);
                    saving.configure(List.of()); saving.succeeds(saving.body(), 0);
                    saving.configure(List.of(gateBinding, observer, queryObserver));
                    saving.succeeds(saving.body(), 2);
                    assertThat(events).extracting(OperationHook.Invocation::phase).containsExactly(OperationHook.Phase.BEFORE_INVOKE, OperationHook.Phase.AFTER_COMPLETION);
                    assertThat(events).allMatch(event -> event.input().equals("{\"marker\":\"approved\"}"));
                    verdict = OperationHook.Verdict.REJECT;
                    var blocked = saving.body();
                    http.perform(post(client.base + "/save?marker=approved").contentType("application/json").content(blocked.toString())).andExpect(status().isForbidden());
                    assertThat(client.get("").path("revision").asLong()).isEqualTo(blocked.path("revision").asLong());
                    verdict = OperationHook.Verdict.CHALLENGE;
                    var preparation = new HookRequestFilter.Preparation("POST", client.base + "/save?marker=approved", Map.of(), "", Map.of(), false,
                            Map.of("/revision", blocked.path("revision")));
                    var challenged = http.perform(post(client.base + "/save?marker=approved").contentType("application/json")
                            .requestAttr(HookRequestFilter.PREPARATION, preparation)).andExpect(status().isConflict()).andReturn().getResponse();
                    String ticket = HookRequestFilter.JSON.readTree(challenged.getContentAsByteArray()).path("token").asText();
                    var subject = challenged.getCookies();
                    assertThat(ticket).isNotEmpty();
                    http.perform(post(client.base + "/save?marker=approved").contentType("application/json").cookie(subject)
                            .requestAttr(HookRequestFilter.PREPARATION, new HookRequestFilter.Preparation(preparation.method(), preparation.url(), Map.of(),
                                    ticket, Map.of(hook.id(), "valid"), false, preparation.json())))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("HOOK_READY"));
                    events.clear();
                    http.perform(post(client.base + "/save?marker=approved").contentType("application/json").cookie(subject)
                            .header(HookChallenges.TICKET_HEADER, ticket).content(blocked.toString())).andExpect(status().isOk());
                    assertThat(events).hasSize(2);
                    assertThat(client.get("").path("draft")).isEqualTo(document);
                    verdict = OperationHook.Verdict.CONTINUE;
                    ((com.fasterxml.jackson.databind.node.ArrayNode) document.at("/project/pages/0/frames/0/component/components/0/components")).removeAll();
                    for (String path : List.of("/document", "/document/title")) {
                        var incompatible = new OperationBinding("invalid-observer", hook.id(), "", save, OperationHook.Phase.AFTER_COMPLETION,
                                0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.JSON, path, "revision")));
                        saving.configure(List.of(gateBinding, incompatible, observer)); saving.succeeds(saving.body(), 2);
                    }
                    saving.configure(List.of(observer)); events.clear();
                    var plain = saving.body();
                    http.perform(post(client.base + "/save").contentType("application/json").content("{broken"))
                            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAGE_DOCUMENT_INVALID"));
                    assertThat(client.get("").path("revision").asLong()).isEqualTo(plain.path("revision").asLong());
                    http.perform(post(client.base + "/save").contentType("text/plain").content(plain.toString())).andExpect(status().isOk());
                    assertThat(events).isEmpty();
                    var requiredJson = new OperationBinding("json-gate", hook.id(), "", save, OperationHook.Phase.BEFORE_INVOKE,
                            0, OperationHook.Completion.ALL, "{}", observer.fields());
                    saving.configure(List.of(requiredJson, queryObserver));
                    var invalid = saving.body();
                    http.perform(post(client.base + "/save?marker=approved").contentType("text/plain").content(invalid.toString())).andExpect(status().isServiceUnavailable());
                    assertThat(client.get("").path("revision").asLong()).isEqualTo(invalid.path("revision").asLong());
                    admissions.closeAndDrain(Set.of(save), java.time.Duration.ZERO); admissions.reopen(Set.of(save));
                    inputSchema = "";
                }
            }
        }
    }

    private void publish(String endpoint) {
        var bindings = new ArrayList<WebPublicationStore.HookRequirement>();
        var resolution = endpoints.resolve(null, new OperationTargets.Reference(endpoint.isEmpty() ? OperationTargets.Kind.OPERATION : OperationTargets.Kind.ENDPOINT,
                endpoint.isEmpty() ? "core:save" : endpoint, ""));
        for (var phase : OperationHook.Phase.values()) bindings.add(new WebPublicationStore.HookRequirement(
                new OperationBinding("binding-" + phase, hook.id(), endpoint.isEmpty() ? "core:save" : "", endpoint,
                        phase, 0, OperationHook.Completion.ALL, "{}"), "core:save", SCHEMA, SCHEMA,
                "unknown-owner", "unknown-owner", hook.definition(), top.sywyar.pixivdownload.plugin.web.page.HookPublications.targetContract(resolution.endpoints()),
                endpoints.targetAnchors(resolution.endpoints())));
        var before = store.snapshot();
        store.commit(before.revision(), new WebPublicationStore.Snapshot(before.revision() + 1, before.epoch(),
                Map.of("core:form", new WebPublicationStore.Page("{}", "{}", "", List.of(), List.of(), bindings))));
    }

    @Test @DisplayName("操作绑定覆盖两个接口及别名，成功失败和阻断的后置语义不同")
    void outcomesAndAliases() throws Exception {
        for (String path : List.of("/hooks/save", "/hooks/alias", "/hooks/second")) mvc.perform(post(path)).andExpect(status().isOk());
        assertThat(calls).hasValue(3);
        assertThat(events.stream().filter(event -> event.phase() == OperationHook.Phase.AFTER_COMPLETION))
                .allMatch(event -> event.outcome() == OperationHook.Outcome.SUCCEEDED);
        events.clear();
        mvc.perform(post("/hooks/save").param("fail", "true")).andExpect(status().isInternalServerError());
        assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.FAILED);
        events.clear(); verdict = OperationHook.Verdict.REJECT;
        mvc.perform(post("/hooks/save")).andExpect(status().isForbidden());
        assertThat(calls).hasValue(3);
        assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.BLOCKED);
        assertThat(events.get(0).input()).isEqualTo("{}");
    }

    @Test @DisplayName("接口绑定只覆盖真实目标，解析器保留多接口和多动作歧义")
    void targetsAndEndpointBinding() throws Exception {
        var resolved = gate.host().getFormEndpoints("core:form", "save");
        assertThat(resolved.status()).isEqualTo(OperationTargets.Status.AVAILABLE);
        assertThat(resolved.endpoints()).hasSize(2);
        assertThat(resolved.endpoints().stream().flatMap(value -> value.routes().stream())).hasSize(3);
        assertThat(gate.host().getFormEndpoints("core:form", "").status()).isEqualTo(OperationTargets.Status.AMBIGUOUS);
        String endpoint = resolved.endpoints().stream().filter(value -> value.routes().stream().anyMatch(route -> route.template().equals("/hooks/save"))).findFirst().orElseThrow().id();
        assertThat(endpoints.resolve("unrelated", new OperationTargets.Reference(OperationTargets.Kind.ENDPOINT, endpoint, "")).status())
                .isEqualTo(OperationTargets.Status.FORBIDDEN);
        assertThat(gate.host().getFormEndpoints("core:form", "missing").status()).isEqualTo(OperationTargets.Status.UNDECLARED);
        publish(endpoint); verdict = OperationHook.Verdict.REJECT;
        mvc.perform(post("/hooks/alias")).andExpect(status().isForbidden());
        mvc.perform(post("/hooks/second")).andExpect(status().isOk());
        mvc.perform(post("/hooks/save").param("endpointId", "core:save-second")).andExpect(status().isForbidden());
    }

    @Test @DisplayName("共享分组接口的页面绑定增删改必须一致，不混入同组另一接口")
    void sharedEndpointPages() throws Exception {
        var declarations = List.of("core:a", "core:b", "core:c").stream().map(id -> new EditablePage(id, "page", "test", "/" + id,
                false, List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(), List.of())).toList();
        var pages = new PageContributionRegistry(declarations, registry);
        var shared = new WebPublicationStore(root.resolve("shared"), root.resolve("shared-enrolled"));
        var admissions = new OperationAdmissions();
        var publisher = new top.sywyar.pixivdownload.plugin.web.page.WebPublications(shared, pages, registry, admissions, List.of());
        publisher.hooks(new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, endpoints, pages, shared, admissions));
        var save = endpoints.catalog(null).stream().filter(value -> value.handler().endsWith("#save")).findFirst().orElseThrow();
        var second = endpoints.catalog(null).stream().filter(value -> value.handler().endsWith("#second")).findFirst().orElseThrow();
        assertThat(save.operation()).isEqualTo(second.operation()).isNotEqualTo(save.id());
        var initial = publisher.prepare(0, List.of(endpointChange("core:a", save.id(), -1), endpointChange("core:b", save.id(), -1),
                endpointChange("core:c", second.id(), 0)));
        publisher.publish(initial, initial.permissions());
        for (int order : List.of(0, 1, -1)) {
            long revision = shared.snapshot().revision();
            var a = endpointChange("core:a", save.id(), order);
            assertThatThrownBy(() -> publisher.prepare(revision, List.of(a)))
                    .isInstanceOfSatisfying(PagePublications.Conflict.class, conflict -> assertThat(conflict.pages()).containsExactly("core:b"));
            assertThat(shared.snapshot().revision()).isEqualTo(revision);
            assertThatThrownBy(() -> publisher.prepare(revision, List.of(a, endpointChange("core:b", save.id(), order == 0 ? 1 : 0))))
                    .hasMessage("PAGE_OPERATION_CONFLICT");
            var plan = publisher.prepare(revision, List.of(a, endpointChange("core:b", save.id(), order)));
            assertThat(plan.impacts().stream().filter(impact -> impact.operation().equals(save.id())).findFirst().orElseThrow().after())
                    .hasSize(order < 0 ? 0 : 1);
            publisher.publish(plan, plan.permissions());
            assertThat(shared.snapshot().hooks(save.operation(), save.id())).hasSize(order < 0 ? 0 : 1);
            assertThat(shared.snapshot().hooks(second.operation(), second.id())).hasSize(1);
        }
        for (String group : List.of(save.operation(), "core:custom")) {
            if (group.equals("core:custom")) {
                var before = shared.snapshot();
                shared.commit(before.revision(), new WebPublicationStore.Snapshot(before.revision() + 1, before.epoch(), before.pages(),
                        new WebPublicationStore.HookState(new WebPublicationStore.HookSettings(Map.of(group, List.of(save.id())), List.of()), List.of(), List.of())));
                endpoints.publications(shared);
            }
            var b = endpointChange("core:b", save.id(), -1);
            var document = (com.fasterxml.jackson.databind.node.ObjectNode) HookRequestFilter.JSON.readTree(b.document());
            ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("actions").get(0)).put("endpoint", "").put("operation", group);
            var mixed = new PagePublications.Change(b.page(), document.toString(), b.projection());
            long revision = shared.snapshot().revision();
            var conflicting = mixed;
            assertThatThrownBy(() -> publisher.prepare(revision, List.of(endpointChange("core:a", save.id(), 0), conflicting)))
                    .hasMessage("PAGE_OPERATION_CONFLICT");
            // 操作引用覆盖 second；它也必须明确声明该接口现有的保护。
            if (group.equals(save.operation())) {
                document.set("hooks", HookRequestFilter.JSON.readTree(endpointChange("core:c", second.id(), 0).document()).path("hooks"));
                mixed = new PagePublications.Change(b.page(), document.toString(), b.projection());
            }
            var plan = publisher.prepare(revision, List.of(mixed)); publisher.publish(plan, plan.permissions());
            assertThatThrownBy(() -> publisher.prepare(shared.snapshot().revision(), List.of(endpointChange("core:a", save.id(), 0))))
                    .isInstanceOfSatisfying(PagePublications.Conflict.class, conflict -> assertThat(conflict.pages()).containsExactly("core:b"));
            for (int order : List.of(0, 1, -1)) {
                var a = endpointChange("core:a", save.id(), order);
                long current = shared.snapshot().revision();
                assertThatThrownBy(() -> publisher.prepare(current, List.of(a)))
                        .isInstanceOfSatisfying(PagePublications.Conflict.class, conflict -> assertThat(conflict.pages()).containsExactly("core:b"));
                assertThatThrownBy(() -> publisher.prepare(current, List.of(a, new PagePublications.Change("core:b",
                        shared.snapshot().pages().get("core:b").document(), b.projection())))).hasMessage("PAGE_OPERATION_CONFLICT");
                var hooks = document.putArray("hooks");
                if (group.equals(save.operation())) HookRequestFilter.JSON.readTree(endpointChange("core:c", second.id(), 0).document()).path("hooks").forEach(hooks::add);
                HookRequestFilter.JSON.readTree(a.document()).path("hooks").forEach(hooks::add);
                var batch = publisher.prepare(current, List.of(a, new PagePublications.Change("core:b", document.toString(), b.projection())));
                publisher.publish(batch, batch.permissions());
                assertThat(shared.snapshot().hooks(save.operation(), save.id())).hasSize(order < 0 ? 0 : 1);
                assertThat(shared.snapshot().hooks(second.operation(), second.id())).hasSize(1);
            }
        }
    }

    @Test @DisplayName("原生票据仅由匹配请求消费，无关观察请求及显式错误票据不盗用证明")
    void nativeTickets() throws Exception {
        verdict = OperationHook.Verdict.CHALLENGE;
        var catalog = endpoints.catalog(null);
        String save = catalog.stream().filter(value -> value.handler().endsWith("#save")).findFirst().orElseThrow().id();
        publish(save);
        var second = catalog.stream().filter(value -> value.handler().endsWith("#second")).findFirst().orElseThrow();
        var snapshot = store.snapshot();
        var bindings = new ArrayList<>(snapshot.pages().get("core:form").hooks());
        bindings.add(new WebPublicationStore.HookRequirement(new OperationBinding("observe", hook.id(), "", second.id(),
                OperationHook.Phase.AFTER_COMPLETION, 0, OperationHook.Completion.ALL, "{}"), second.operation(), "", "",
                "unknown-owner", "unknown-owner", hook.definition(), top.sywyar.pixivdownload.plugin.web.page.HookPublications.targetContract(List.of(second))));
        store.commit(snapshot.revision(), new WebPublicationStore.Snapshot(snapshot.revision() + 1, snapshot.epoch(), Map.of("core:form",
                new WebPublicationStore.Page("{}", "{}", "", List.of(), List.of(), bindings))));
        var challenge = mvc.perform(post("/hooks/save")).andExpect(status().isConflict()).andReturn().getResponse();
        var client = challenge.getCookie("pixiv_hook_client");
        String token = HookRequestFilter.JSON.readTree(challenge.getContentAsString()).path("token").asText();
        var staged = mvc.perform(post("/hooks/save").cookie(client).requestAttr(HookRequestFilter.PREPARATION,
                new HookRequestFilter.Preparation("POST", "/hooks/save", Map.of(), token, Map.of(hook.id(), "valid"), true, Map.of())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("HOOK_READY")).andReturn().getResponse();
        var ticket = Arrays.stream(staged.getCookies()).filter(cookie -> cookie.getName().startsWith("pixiv_hook_ticket")).findFirst().orElseThrow();
        mvc.perform(post("/hooks/second").cookie(client, ticket)).andExpect(status().isOk()).andExpect(result -> assertThat(result.getResponse().getCookies()).isEmpty());
        mvc.perform(post("/hooks/second").cookie(client, ticket).header(HookChallenges.TICKET_HEADER, token)).andExpect(status().isConflict());
        mvc.perform(post("/hooks/save").cookie(client, ticket)).andExpect(status().isOk());
        mvc.perform(post("/hooks/save").cookie(client, ticket)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOOK_STALE"));
        assertThat(calls).hasValue(2);

        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenReturn(1000L);
        var bounded = new HookChallenges(clock);
        var target = new HookChallenges.Target("client", "scope", "revision");
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var issued = new org.springframework.mock.web.MockHttpServletResponse();
        var tokens = new ArrayList<String>();
        for (int index = 0; index < 17; index++) {
            String next = UUID.randomUUID().toString(); tokens.add(next);
            assertThat(bounded.retain(next, target.digest(), List.of())).isTrue();
            assertThat(bounded.stage(next, target.digest(), List.of(), Map.of(hook.id(), "valid"))).isTrue();
            if (index < 16) bounded.nativeTicket(request, issued, next, target);
            else assertThatThrownBy(() -> bounded.nativeTicket(request, issued, next, target)).hasMessage("HOOK_UNAVAILABLE");
        }
        request.setCookies(issued.getCookies()[0]);
        assertThat(bounded.consume(request, issued, new HookChallenges.Target("client", "scope", "changed-revision"), List.of()).proof()).isNull();
        assertThat(bounded.consume(request, issued, target, List.of()).proof()).isNotNull();
        bounded.nativeTicket(request, issued, tokens.get(16), target);
        org.mockito.Mockito.when(clock.millis()).thenReturn(301_001L);
        request.addHeader(HookChallenges.TICKET_HEADER, tokens.get(16));
        assertThat(bounded.consume(request, issued, target, List.of()).proof()).isNull();
    }

    private PagePublications.Change endpointChange(String page, String endpoint, int order) {
        var document = HookRequestFilter.JSON.createObjectNode().put("format", 1);
        document.putArray("actions").addObject().put("id", "save").put("formId", "form").put("actionId", "save")
                .put("region", "form").put("operation", "").put("endpoint", endpoint);
        document.set("hooks", HookRequestFilter.JSON.valueToTree(order < 0 ? List.of() : List.of(new OperationBinding("page-hook-" + endpoint, hook.id(), "", endpoint,
                OperationHook.Phase.BEFORE_INVOKE, order, OperationHook.Completion.ALL, "{}"))));
        return new PagePublications.Change(page, document.toString(), "{\"format\":1,\"pageId\":\"" + page
                + "\",\"title\":\"Form\",\"body\":{\"type\":\"wrapper\",\"children\":[{\"type\":\"page-region\",\"region\":\"form\",\"children\":[]}]}}");
    }

    @Test @DisplayName("后置故障保留成功结果，必需提供者撤回拒绝业务且不重放")
    void optionalFailureAndWithdrawal() throws Exception {
        afterFailure = true;
        mvc.perform(post("/hooks/save")).andExpect(status().isOk()).andExpect(content().string("1"));
        assertThat(calls).hasValue(1);
        registry.withdraw(provider);
        mvc.perform(post("/hooks/save")).andExpect(status().isServiceUnavailable());
        assertThat(calls).hasValue(1);
    }

    @Test @DisplayName("非 HTTP 同步操作的超时回调实际退出前不释放租约，不接受迟到通过")
    void actualDrainAndTimeout() throws Exception {
        timeout = true; entered = new CountDownLatch(1); released = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> gate.host().execute("core:save", "subject", "{}", "", Map.of(), calls::incrementAndGet).status());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.withdraw(provider).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            released.countDown();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(OperationGuard.Status.UNAVAILABLE);
            assertThat(calls).hasValue(0); assertThat(drain.isDrained()).isTrue();
        } finally { released.countDown(); executor.shutdownNow(); }
    }

    @RestController static final class Controller {
        private final AtomicInteger calls;
        Controller(AtomicInteger calls) { this.calls = calls; }
        @PostMapping({"/hooks/save", "/hooks/alias"})
        @OperationEndpoint(operation = "core:save")
        ResponseEntity<?> save(@RequestParam(defaultValue = "false") boolean fail) { return fail ? ResponseEntity.internalServerError().build() : ResponseEntity.ok(calls.incrementAndGet()); }
        @PostMapping("/hooks/second")
        @OperationEndpoint(operation = "core:save")
        ResponseEntity<?> second() { return ResponseEntity.ok(calls.incrementAndGet()); }
    }

    @Test @DisplayName("无注解与 Guard 的原样控制器经真实 HTTP 自动拦截并记录来源")
    void automaticHttp() throws Exception {
        var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        var factory = new StaticListableBeanFactory();
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("core", List.of(WebRouteContribution.admin("/automatic/**")));
        var pageCatalog = new PageContributionRegistry(List.of(new EditablePage("core:automatic-page", "page", "test", "/automatic/page", false,
                List.of(new PageRegion("form", "form", "", true, false)), "", "", List.of(), List.of())), registry);
        var catalog = new OperationEndpoints(factory.getBeanProvider(RequestMappingHandlerMapping.class), routes, pageCatalog);
        catalog.publications(store);
        var automatic = new PlainController();
        var admissions = new OperationAdmissions();
        var contracts = new top.sywyar.pixivdownload.plugin.web.page.HookPublications(registry, catalog, pageCatalog, store, admissions);
        var interception = new HttpOperationHooks(catalog, store, registry, admissions, new HookChallenges(), contracts, List.of(), hookExecutor);
        var identities = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver.class);
        org.mockito.Mockito.when(identities.isAdminAuthenticated(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                "fixture".equals(((jakarta.servlet.http.HttpServletRequest) call.getArgument(0)).getHeader("X-Test-Admin")));
        factory.addBean("csrf", new top.sywyar.pixivdownload.setup.CsrfProtectionFilter(
                org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.AppLocaleResolver.class),
                org.mockito.Mockito.mock(top.sywyar.pixivdownload.i18n.AppMessages.class), routes));
        var preparations = new HookRequestFilter(factory.getBeanProvider(RequestMappingHandlerMapping.class),
                factory.getBeanProvider(top.sywyar.pixivdownload.setup.CsrfProtectionFilter.class));
        context.register(AutomaticWeb.class);
        context.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("plain", automatic);
            beans.registerSingleton("sdk", new top.sywyar.pixivdownload.plugin.web.resource.SdkWebResourcesConfiguration());
            beans.registerSingleton("hook-configuration", new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController(catalog, registry, identities, contracts, store));
            beans.registerSingleton("hooks", new org.springframework.web.servlet.handler.MappedInterceptor(null, interception));
        });
        var tomcat = new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0);
        tomcat.setAddress(java.net.InetAddress.getLoopbackAddress());
        tomcat.setBaseDirectory(root.resolve("tomcat").toFile());
        var server = tomcat.getWebServer(servletContext -> {
            servletContext.addListener(interception);
            context.setServletContext(servletContext); context.refresh();
            var servlet = servletContext.addServlet("mvc", new org.springframework.web.servlet.DispatcherServlet(context));
            servlet.setAsyncSupported(true); servlet.setLoadOnStartup(1); servlet.addMapping("/");
            var filter = servletContext.addFilter("hook-preparation", preparations); filter.setAsyncSupported(true);
            filter.addMappingForUrlPatterns(EnumSet.of(jakarta.servlet.DispatcherType.REQUEST), false, "/*");
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OperationHookCalls.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        Locale previous = Locale.getDefault(); Locale.setDefault(Locale.CHINA);
        try {
            factory.addBean("mapping", context.getBean(RequestMappingHandlerMapping.class));
            server.start();
            var client = java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build();
            String origin = "http://localhost:" + server.getPort();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/save"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"value\":\"synthetic-private-field\"}")).build();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(automatic.executions).hasValue(1);
            var endpoint = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#save")).findFirst().orElseThrow();
            assertThat(endpoint.routes()).hasSize(2);
            var pagePublisher = new top.sywyar.pixivdownload.plugin.web.page.WebPublications(store, pageCatalog, registry, admissions, List.of());
            pagePublisher.hooks(contracts);
            var grouped = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#grouped")).findFirst().orElseThrow();
            assertThat(grouped.operation()).isEqualTo("core:grouped").isNotEqualTo(grouped.id());
            for (int change = 0; change < 3; change++) {
                var groupedDocument = HookRequestFilter.JSON.createObjectNode().put("format", 1);
                var groupedActions = groupedDocument.putArray("actions");
                if (change < 2) groupedActions.addObject().put("id", "grouped").put("formId", "grouped-form").put("actionId", "save")
                        .put("region", "form").put("operation", "").put("endpoint", grouped.id());
                groupedDocument.set("hooks", HookRequestFilter.JSON.valueToTree(change == 2 ? List.of() : List.of(
                        new OperationBinding("grouped", hook.id(), "", grouped.id(), OperationHook.Phase.BEFORE_INVOKE,
                                change, OperationHook.Completion.ALL, "{}"))));
                var groupedChange = new PagePublications.Change("core:automatic-page", groupedDocument.toString(),
                        "{\"format\":1,\"pageId\":\"core:automatic-page\",\"title\":\"Grouped\",\"body\":{\"type\":\"wrapper\",\"children\":[{\"type\":\"page-region\",\"region\":\"form\",\"children\":[]}]}}");
                var groupedPlan = pagePublisher.prepare(store.snapshot().revision(), List.of(groupedChange));
                automatic.groupedEntered = new CountDownLatch(1); automatic.groupedRelease = new CountDownLatch(1);
                var pendingGrouped = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/grouped"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(automatic.groupedEntered.await(5, TimeUnit.SECONDS)).isTrue();
                long beforePublish = store.snapshot().revision();
                var publication = CompletableFuture.supplyAsync(() -> pagePublisher.publish(groupedPlan, groupedPlan.permissions()));
                try {
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> {
                        try (var ignored = admissions.enter(grouped.id())) { return false; }
                        catch (IllegalStateException closed) { return closed.getMessage().equals("OPERATION_PUBLICATION_BUSY"); }
                    });
                    assertThatThrownBy(() -> publication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    assertThat(store.snapshot().revision()).isEqualTo(beforePublish);
                } finally { automatic.groupedRelease.countDown(); }
                assertThat(pendingGrouped.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(publication.get(5, TimeUnit.SECONDS)).isEqualTo(beforePublish + 1);
            }
            var document = HookRequestFilter.JSON.createObjectNode().put("format", 1);
            var relations = document.putArray("actions");
            for (String action : List.of("save", "publish")) relations.addObject().put("id", action).put("formId", "editor")
                    .put("actionId", action).put("region", "form").put("operation", "").put("endpoint", endpoint.id());
            document.set("hooks", HookRequestFilter.JSON.valueToTree(Arrays.stream(OperationHook.Phase.values()).map(phase ->
                    new OperationBinding("page-" + phase, hook.id(), "", endpoint.id(), phase, 0, OperationHook.Completion.ALL, "{}")).toList()));
            var pageChange = new PagePublications.Change("core:automatic-page", document.toString(),
                    "{\"format\":1,\"pageId\":\"core:automatic-page\",\"title\":\"Form\",\"body\":{\"type\":\"wrapper\",\"children\":[{\"type\":\"page-region\",\"region\":\"form\",\"children\":[]}]}}");
            var pagePlan = pagePublisher.prepare(store.snapshot().revision(), List.of(pageChange));
            pagePublisher.publish(pagePlan, pagePlan.permissions());
            assertThat(catalog.resolve(null, new OperationTargets.Reference(OperationTargets.Kind.FORM_ACTION, "core:automatic-page", "editor", "")).status())
                    .isEqualTo(OperationTargets.Status.AMBIGUOUS);
            assertThat(catalog.resolve(null, new OperationTargets.Reference(OperationTargets.Kind.FORM_ACTION, "core:automatic-page", "editor", "save")).endpoints())
                    .containsExactly(endpoint);
            verdict = OperationHook.Verdict.REJECT;
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(automatic.executions).hasValue(1);
            var pageRevision = store.snapshot();
            store.commit(pageRevision.revision(), new WebPublicationStore.Snapshot(pageRevision.revision() + 1, pageRevision.epoch(), Map.of()));
            verdict = OperationHook.Verdict.CONTINUE;
            var settings = new WebPublicationStore.HookSettings(Map.of("core:neutral-group", List.of(endpoint.id())), Arrays.stream(OperationHook.Phase.values())
                    .map(phase -> new OperationBinding("group-" + phase, hook.id(), "core:neutral-group", "", phase, 0, OperationHook.Completion.ALL, "{}")).toList());
            var configuration = new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.BindingConfiguration(store.snapshot().revision(), settings);
            var planRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/web/operation-hooks/prepare"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(HookRequestFilter.JSON.writeValueAsString(configuration))).build();
            assertThat(client.send(planRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            var preview = client.send(java.net.http.HttpRequest.newBuilder(planRequest, (name, value) -> true).header("X-Test-Admin", "fixture").build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(preview.statusCode()).isEqualTo(200);
            var plan = HookRequestFilter.JSON.readValue(preview.body(), top.sywyar.pixivdownload.plugin.web.page.HookPublications.ConfigurationPlan.class);
            var publish = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/web/operation-hooks/publish"))
                    .header("Content-Type", "application/json").header("X-Test-Admin", "fixture");
            assertThat(client.send(publish.POST(java.net.http.HttpRequest.BodyPublishers.ofString(HookRequestFilter.JSON.writeValueAsString(
                    new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.Publication(plan, List.of())))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(store.snapshot().revision()).isEqualTo(configuration.revision());
            assertThat(client.send(publish.POST(java.net.http.HttpRequest.BodyPublishers.ofString(HookRequestFilter.JSON.writeValueAsString(
                    new top.sywyar.pixivdownload.plugin.web.controller.OperationHooksController.Publication(plan, plan.permissions())))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            verdict = OperationHook.Verdict.REJECT; events.clear();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(automatic.executions).hasValue(1);
            assertThat(events).hasSize(2);
            assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.BLOCKED);
            verdict = OperationHook.Verdict.CONTINUE; afterFailure = true; events.clear();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(automatic.executions).hasValue(2);
            assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.SUCCEEDED);
            assertThat(events.get(0).input()).isEqualTo("{}");
            assertThat(events.get(1).http().status()).isEqualTo(200);
            afterFailure = false;
            verdict = OperationHook.Verdict.CHALLENGE;
            var prepare = new HookRequestFilter.Preparation("POST", "/automatic/save", Map.of("content-type", "application/json"), "", Map.of(), false, Map.of());
            var challenge = client.send(prepareRequest(origin, prepare), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(challenge.statusCode()).isEqualTo(409);
            String token = HookRequestFilter.JSON.readTree(challenge.body()).path("token").asText();
            assertThat(token).isNotBlank(); assertThat(automatic.executions).hasValue(2);
            var supplied = new HookRequestFilter.Preparation("POST", "/automatic/save", prepare.headers(), token, Map.of(hook.id(), "valid"), false, Map.of());
            assertThat(client.send(prepareRequest(origin, supplied), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(automatic.executions).hasValue(2);
            var proven = java.net.http.HttpRequest.newBuilder(request, (name, value) -> true).header(HookChallenges.TICKET_HEADER, token).build();
            var first = client.sendAsync(proven, java.net.http.HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(proven, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
            assertThat(automatic.executions).hasValue(3);
            assertThat(client.send(proven, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
            if (Boolean.getBoolean("hooks.browser")) {
                var browserEndpoints = catalog.catalog(null).stream().filter(value -> Set.of("save", "nativeForm", "upload", "observe", "draft", "publishForm", "headerDraft", "headerPublish", "json", "text", "item", "code", "files")
                        .stream().anyMatch(name -> value.handler().endsWith("#" + name))).toArray(OperationTargets.Endpoint[]::new);
                publishAutomatic(browserEndpoints);
                var script = java.nio.file.Paths.get(getClass().getResource("/page-components/http-hook-browser.mjs").toURI());
                var process = new ProcessBuilder("node", script.toString()).redirectErrorStream(true).redirectOutput(root.resolve("browser.log").toFile());
                process.environment().put("PAGE_ORIGIN", origin); process.environment().put("PAGE_REVISION", store.snapshot().identity());
                process.environment().put("PAGE_ENDPOINTS", HookRequestFilter.JSON.writeValueAsString(Arrays.stream(browserEndpoints)
                        .collect(java.util.stream.Collectors.toMap(value -> value.handler().substring(value.handler().lastIndexOf('#') + 1), OperationTargets.Endpoint::id))));
                var browser = process.start();
                try {
                    assertThat(browser.waitFor(120, TimeUnit.SECONDS)).isTrue();
                    assertThat(browser.exitValue()).withFailMessage(java.nio.file.Files.readString(root.resolve("browser.log"))).isZero();
                    System.out.print(java.nio.file.Files.readString(root.resolve("browser.log")));
                    assertThat(automatic.dynamicCalls).hasValue(10);
                } finally { if (browser.isAlive()) browser.destroyForcibly(); }
            }
            verdict = OperationHook.Verdict.CONTINUE;
            inputSchema = "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"},\"marker\":{\"type\":\"string\"}},\"required\":[\"value\",\"marker\"],\"additionalProperties\":false}";
            resultSchema = "{\"type\":\"object\",\"properties\":{\"calls\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647}},\"required\":[\"calls\"],\"additionalProperties\":false}";
            var inputFields = List.of(new OperationBinding.Field(OperationBinding.Source.JSON, "/value", "value"),
                    new OperationBinding.Field(OperationBinding.Source.QUERY, "marker", "marker"));
            var outputFields = new ArrayList<>(inputFields); outputFields.add(new OperationBinding.Field(OperationBinding.Source.RESULT, "/calls", "calls"));
            var fieldBindings = List.of(new OperationBinding("input", hook.id(), "", endpoint.id(), OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}", inputFields),
                    new OperationBinding("result", hook.id(), "", endpoint.id(), OperationHook.Phase.AFTER_COMPLETION, 0, OperationHook.Completion.ALL, "{}", outputFields));
            var old = store.snapshot(); store.commit(old.revision(), new WebPublicationStore.Snapshot(old.revision() + 1, old.epoch(), Map.of()));
            var fieldPlan = contracts.prepareConfiguration(store.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), fieldBindings));
            contracts.publishConfiguration(fieldPlan, fieldPlan.permissions());
            events.clear();
            var projectedRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/alias?marker=approved&password=synthetic-query-secret"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"synthetic-private-field\"}")).build();
            var projectedResponse = client.send(projectedRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(projectedResponse.statusCode()).isEqualTo(200);
            long projectedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < 2 && System.nanoTime() < projectedBy) Thread.sleep(10);
            assertThat(HookRequestFilter.JSON.readTree(events.get(0).input()).fieldNames()).toIterable().containsExactlyInAnyOrder("value", "marker");
            assertThat(HookRequestFilter.JSON.readTree(events.get(1).result()).fieldNames()).toIterable().containsExactly("calls");
            assertThat(HookRequestFilter.JSON.readTree(projectedResponse.body()).path("value").asText()).isEqualTo("synthetic-private-field");
            int executed = automatic.executions.get();
            assertThat(client.send(java.net.http.HttpRequest.newBuilder(projectedRequest, (name, value) -> true)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"" + "x".repeat(65536) + "\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            assertThat(automatic.executions).hasValue(executed);
            var observerPlan = contracts.prepareConfiguration(store.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of(fieldBindings.get(1))));
            contracts.publishConfiguration(observerPlan, observerPlan.permissions());
            events.clear();
            String largeValue = "synthetic-private-field".repeat(4000);
            var observedResponse = client.send(java.net.http.HttpRequest.newBuilder(projectedRequest, (name, value) -> true)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"" + largeValue + "\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(observedResponse.statusCode()).isEqualTo(200);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(observedResponse.body()).path("value").asText()).isEqualTo(largeValue);
            assertThat(automatic.executions).hasValue(executed + 1);
            admissions.closeAndDrain(Set.of(endpoint.id()), java.time.Duration.ofSeconds(5)); admissions.reopen(Set.of(endpoint.id()));
            assertThat(events).isEmpty();
            inputSchema = "";
            var resultContract = HookRequestFilter.JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
            resultContract.putArray("required");
            var resultProperties = resultContract.putObject("properties");
            resultProperties.putObject("value").put("type", "string");
            resultProperties.putObject("calls").put("type", "integer").put("minimum", 0).put("maximum", Integer.MAX_VALUE);
            for (int part = 0; part < 5; part++) resultProperties.putObject("part" + part).put("type", "string");
            resultSchema = resultContract.toString();
            for (int badOrder : List.of(-1, 1)) {
                for (String scenario : List.of("short", "field-limit", "total-limit", "incompatible", "capture-limit")) {
                    String value = "short".equals(scenario) || "incompatible".equals(scenario) ? "synthetic-private-field"
                            : "synthetic-private-field" + "x".repeat("field-limit".equals(scenario) ? 5000 : "total-limit".equals(scenario) ? 3900 : 70000);
                    var badFields = new ArrayList<OperationBinding.Field>();
                    if (scenario.equals("total-limit")) {
                        for (int part = 0; part < 5; part++) badFields.add(new OperationBinding.Field(OperationBinding.Source.RESULT, "/value", "part" + part));
                    } else badFields.add(new OperationBinding.Field(OperationBinding.Source.RESULT, "/value", scenario.equals("incompatible") ? "calls" : "value"));
                    var badResult = new OperationBinding("bad-result", hook.id(), "", endpoint.id(), OperationHook.Phase.AFTER_COMPLETION,
                            badOrder, OperationHook.Completion.ALL, "{}", badFields);
                    var goodResult = new OperationBinding("good-result", hook.id(), "", endpoint.id(), OperationHook.Phase.AFTER_COMPLETION,
                            0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.RESULT, "/calls", "calls")));
                    var resultPlan = contracts.prepareConfiguration(store.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), List.of(badResult, goodResult)));
                    contracts.publishConfiguration(resultPlan, resultPlan.permissions());
                    events.clear(); int beforeResult = automatic.executions.get();
                    var resultResponse = client.send(java.net.http.HttpRequest.newBuilder(request, (name, content) -> true)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"" + value + "\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(resultResponse.statusCode()).isEqualTo(200);
                    var originalResult = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultResponse.body());
                    assertThat(originalResult.path("value").asText()).isEqualTo(value);
                    assertThat(originalResult.path("calls").asInt()).isEqualTo(beforeResult + 1);
                    admissions.closeAndDrain(Set.of(endpoint.id()), java.time.Duration.ofSeconds(5)); admissions.reopen(Set.of(endpoint.id()));
                    assertThat(automatic.executions).hasValue(beforeResult + 1);
                    assertThat(events).as("%s badOrder=%s", scenario, badOrder).hasSize(scenario.equals("short") ? 2 : scenario.equals("capture-limit") ? 0 : 1);
                    if (!scenario.equals("capture-limit")) assertThat(events).anySatisfy(event ->
                            assertThat(event.result()).isEqualTo("{\"calls\":" + (beforeResult + 1) + "}"));
                }
            }
            var restoreFields = contracts.prepareConfiguration(store.snapshot().revision(), new WebPublicationStore.HookSettings(Map.of(), fieldBindings));
            contracts.publishConfiguration(restoreFields, restoreFields.permissions());
            assertThatThrownBy(() -> HookContracts.binding(new OperationBinding("forbidden", hook.id(), "", endpoint.id(),
                    OperationHook.Phase.BEFORE_INVOKE, 0, OperationHook.Completion.ALL, "{}",
                    List.of(new OperationBinding.Field(OperationBinding.Source.JSON, "/password", "value"))), hook.definition())).isInstanceOf(IllegalArgumentException.class);
            inputSchema = ""; resultSchema = "";
            var mapping = (top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping) context.getBean(RequestMappingHandlerMapping.class);
            var original = mapping.getHandlerMethods().entrySet().stream().filter(entry -> entry.getValue().getMethod().getName().equals("save"))
                    .findFirst().orElseThrow();
            events.clear(); int beforeReplacement = automatic.executions.get();
            mapping.unregisterMapping(original.getKey());
            try {
                mapping.registerMapping(original.getKey(), automatic, PlainController.class.getMethod("replacement", PlainController.Input.class));
                assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
                mapping.unregisterMapping(original.getKey());
                mapping.registerPluginMapping("replacement-owner", original.getKey(), automatic, original.getValue().getMethod());
                assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
                mapping.unregisterMapping(original.getKey());
                var expanded = original.getKey().mutate().paths("/automatic/save", "/automatic/alias", "/automatic/new").build();
                mapping.registerMapping(expanded, automatic, original.getValue().getMethod());
                try {
                    var newAlias = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/new"))
                            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"unchanged\"}")).build();
                    assertThat(client.send(newAlias, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
                } finally { mapping.unregisterMapping(expanded); }
            } finally {
                mapping.unregisterMapping(original.getKey());
                mapping.registerMapping(original.getKey(), automatic, original.getValue().getMethod());
            }
            assertThat(automatic.executions).hasValue(beforeReplacement); assertThat(events).isEmpty();
            var specific = original.getKey().mutate().paths("/automatic/save").params("mode=specific").build();
            mapping.registerMapping(specific, automatic, original.getValue().getMethod());
            try {
                var selected = catalog.catalog(null).stream().filter(value -> value.parameters().contains("mode=specific")).findFirst().orElseThrow();
                assertThat(selected.id()).isNotEqualTo(endpoint.id()); publishAutomatic(selected); events.clear();
                var conditional = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/save?mode=specific"))
                        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"unchanged\"}")).build();
                assertThat(client.send(conditional, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(events.get(0).endpoint()).isEqualTo(selected.id());
            } finally { mapping.unregisterMapping(specific); }
            for (String path : List.of("async", "deferred", "stream", "sse", "failure")) {
                var selected = catalog.catalog(null).stream().filter(value -> value.routes().stream()
                        .anyMatch(route -> route.template().equals("/automatic/" + path))).findFirst().orElseThrow();
                publishAutomatic(selected); events.clear();
                var result = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/" + path))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(result.statusCode()).isEqualTo(path.equals("failure") ? 500 : 200);
                long completedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (events.size() < 2 && System.nanoTime() < completedBy) Thread.sleep(10);
                assertThat(events).hasSize(2);
                assertThat(events.get(1).outcome()).isEqualTo(path.equals("failure") ? OperationHook.Outcome.FAILED : OperationHook.Outcome.SUCCEEDED);
                assertThat(events.get(1).http().status()).isEqualTo(result.statusCode());
                assertThat(events.get(1).result()).isEqualTo("{}");
            }
            var cancelled = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#cancelled")).findFirst().orElseThrow();
            publishAutomatic(cancelled); events.clear();
            try (var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.getPort())) {
                socket.setSoLinger(true, 0);
                socket.getOutputStream().write(("POST /automatic/cancel HTTP/1.1\r\nHost: localhost:" + server.getPort()
                        + "\r\nContent-Length: 0\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertThat(automatic.streaming.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(events).hasSize(1);
            }
            automatic.disconnect.countDown();
            assertThat(automatic.streamExited.await(5, TimeUnit.SECONDS)).isTrue();
            long cancelledBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < 2 && System.nanoTime() < cancelledBy) Thread.sleep(10);
            assertThat(events).hasSize(2);
            assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.CANCELLED);
            publishAutomatic(endpoint); events.clear();
            entered = new CountDownLatch(1); released = new CountDownLatch(1);
            int beforeTimeout = automatic.executions.get();
            var blocked = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/save"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"value\":\"unchanged\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(blocked.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
            assertThat(events).hasSize(1);
            assertThat(automatic.executions).hasValue(beforeTimeout);
            assertThat(child.isActive()).isTrue();
            released.countDown();
            long callbackEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < 2 && System.nanoTime() < callbackEnd) Thread.sleep(10);
            assertThat(events).hasSize(2);
            assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.BLOCKED);
            assertThat(events.get(1).http().reason()).isEqualTo("HOOK_TIMEOUT");
            entered = null;
            var timed = catalog.catalog(null).stream().filter(value -> value.handler().endsWith("#timeout")).findFirst().orElseThrow();
            publishAutomatic(timed); events.clear();
            var pending = client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/automatic/timeout"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(automatic.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
            var drain = registry.withdraw(provider).orElseThrow();
            assertThat(drain.isDrained()).isFalse();
            assertThat(events).hasSize(1);
            automatic.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!drain.isDrained() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(drain.isDrained()).isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(1).outcome()).isEqualTo(OperationHook.Outcome.TIMED_OUT);
            var text = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(text).contains("Hook invocation started", "Hook invocation completed", "unknown-owner", "#invoke", "#save",
                    "BEFORE_INVOKE", "AFTER_COMPLETION", "pages=core:form", "INPUT_UNAVAILABLE", "RESULT_UNAVAILABLE", "DATA_INCOMPATIBLE")
                    .doesNotContain("synthetic-private-field", "synthetic-query-secret", "private-fixture-error", token);
            assertThat(org.slf4j.MDC.get("operationVerificationId")).isNull();
        } finally {
            automatic.release.countDown(); automatic.disconnect.countDown(); if (released != null) released.countDown();
            Locale.setDefault(previous); logger.detachAppender(logs); logs.stop(); server.stop(); context.close();
        }
    }

    private static java.net.http.HttpRequest prepareRequest(String origin, HookRequestFilter.Preparation preparation) throws IOException {
        return java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + HookRequestFilter.PREPARE_PATH))
                .header("Content-Type", "application/json").header("Origin", origin)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(HookRequestFilter.JSON.writeValueAsString(preparation))).build();
    }

    private void publishAutomatic(OperationTargets.Endpoint... endpoints) {
        var bindings = Arrays.stream(endpoints).flatMap(endpoint -> Arrays.stream(OperationHook.Phase.values())
                .filter(phase -> !endpoint.handler().endsWith("#observe") || phase == OperationHook.Phase.AFTER_COMPLETION).map(phase -> new WebPublicationStore.HookRequirement(
                new OperationBinding("automatic-" + phase, hook.id(), "", endpoint.id(), phase, 0, OperationHook.Completion.ALL, "{}"),
                endpoint.operation(), SCHEMA, SCHEMA, "unknown-owner", "unknown-owner", hook.definition(),
                top.sywyar.pixivdownload.plugin.web.page.HookPublications.targetContract(List.of(endpoint))))).toList();
        var before = store.snapshot();
        store.commit(before.revision(), new WebPublicationStore.Snapshot(before.revision() + 1, before.epoch(),
                Map.of("core:form", new WebPublicationStore.Page("{}", "{}", "", List.of(), List.of(), bindings))));
    }

    @org.springframework.context.annotation.Configuration
    static class AutomaticWeb extends org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport {
        @Override protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
            return new top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping();
        }
        @Override public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/js/**").addResourceLocations("classpath:/static/js/");
            registry.addResourceHandler("/sdk/values/**").addResourceLocations("classpath:/META-INF/pixivdownload/web/");
        }
    }

    @RestController static final class PlainController {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger dynamicCalls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch streaming = new CountDownLatch(1), disconnect = new CountDownLatch(1), streamExited = new CountDownLatch(1);
        volatile CountDownLatch groupedEntered, groupedRelease;
        record Input(String value) { }
        @PostMapping({"/automatic/save", "/automatic/alias"})
        public Map<String, Object> save(@RequestBody Input input) {
            return Map.of("value", input.value(), "calls", executions.incrementAndGet());
        }
        public Map<String, Object> replacement(Input input) { return Map.of("calls", executions.incrementAndGet()); }
        @PostMapping(value = "/automatic/native", params = "mode=chosen")
        public Map<String, Object> nativeForm(@RequestParam String value) { return Map.of("value", value, "calls", executions.incrementAndGet()); }
        @PostMapping("/automatic/upload")
        public Map<String, Object> upload(@RequestBody byte[] body) { return Map.of("bytes", body.length, "calls", executions.incrementAndGet()); }
        @PostMapping("/automatic/observe")
        public String observe() { return "observed"; }
        @OperationEndpoint(operation = "core:grouped") @PostMapping("/automatic/grouped")
        public Callable<String> grouped() { return () -> { groupedEntered.countDown(); groupedRelease.await(); return "done"; }; }
        @PostMapping("/automatic/items/{id}")
        public Map<String, Object> item(@PathVariable String id) { return Map.of("value", id, "calls", dynamicCalls.incrementAndGet()); }
        @PostMapping("/automatic/codes/{id:[A-Z]{2}\\d{2}}.json")
        public Map<String, Object> code(@PathVariable String id) { return Map.of("value", id, "calls", dynamicCalls.incrementAndGet()); }
        @PostMapping("/automatic/files/{*path}")
        public Map<String, Object> files(@PathVariable String path) { return Map.of("value", path, "calls", dynamicCalls.incrementAndGet()); }
        @PostMapping(value = "/automatic/conditional", params = "mode=draft")
        public Map<String, String> draft() { return Map.of("selected", "draft"); }
        @PostMapping(value = "/automatic/conditional", params = "mode=publish")
        public Map<String, String> publishForm() { return Map.of("selected", "publishForm"); }
        @PostMapping(value = "/automatic/header", headers = "X-Mode=draft")
        public Map<String, String> headerDraft() { return Map.of("selected", "headerDraft"); }
        @PostMapping(value = "/automatic/header", headers = "X-Mode=publish")
        public Map<String, String> headerPublish() { return Map.of("selected", "headerPublish"); }
        @PostMapping(value = "/automatic/media", consumes = "application/json", produces = "application/json")
        public Map<String, String> json() { return Map.of("selected", "json"); }
        @PostMapping(value = "/automatic/media", consumes = "text/plain", produces = "application/json")
        public Map<String, String> text() { return Map.of("selected", "text"); }
        @GetMapping(value = "/automatic/page", produces = "text/html;charset=UTF-8")
        public String page() { return "<form method='post' action='/automatic/native' target='result'><input name='value' value='native-value'><input type='hidden' name='mode' value='chosen'><button>Submit</button></form><iframe name='result'></iframe>"; }
        @PostMapping("/automatic/async")
        public Callable<Map<String, Integer>> asynchronous() { return () -> Map.of("calls", executions.incrementAndGet()); }
        @PostMapping("/automatic/deferred")
        public org.springframework.web.context.request.async.DeferredResult<String> deferred() {
            var result = new org.springframework.web.context.request.async.DeferredResult<String>();
            result.setResult("accepted"); return result;
        }
        @PostMapping("/automatic/stream")
        public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream() {
            return output -> { for (int index = 0; index < 32; index++) { output.write(new byte[8192]); output.flush(); } };
        }
        @PostMapping("/automatic/failure")
        public String failure() { throw new IllegalStateException("synthetic-business-failure"); }
        @PostMapping(value = "/automatic/sse", produces = "text/event-stream")
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter sse() throws IOException {
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
            emitter.send("accepted"); emitter.complete(); return emitter;
        }
        @PostMapping("/automatic/cancel")
        public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody cancelled() {
            return output -> {
                streaming.countDown();
                try {
                    disconnect.await();
                    for (int index = 0; index < 32; index++) { output.write(new byte[8192]); output.flush(); }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { streamExited.countDown(); }
            };
        }
        @PostMapping("/automatic/timeout")
        public org.springframework.web.context.request.async.WebAsyncTask<String> timeout() {
            return new org.springframework.web.context.request.async.WebAsyncTask<>(50, () -> {
                started.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                return "late";
            });
        }
    }
}
