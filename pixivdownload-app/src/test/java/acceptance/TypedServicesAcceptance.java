package acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.*;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.*;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.*;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.SigningKeyFiles;
import top.sywyar.pixivdownload.setup.SetupService;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/**
 * 对已解压的固定 boot JAR 执行签名安装和真实 HTTP 验证；业务契约不在宿主 classpath。
 * 编译和运行仅使用该产物根目录、BOOT-INF/classes 及 BOOT-INF/lib/*。
 * 参数依次为独立样例目录、SQLite 插件 JAR、下载工作台 JAR、故障夹具目录和 JS HTTP 探针。
 * 夹具由 test/resources/typed-services/build.py 以独立 SDK 仓库和已解压宿主目录构建。
 */
public final class TypedServicesAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path root;
    private static PluginSupplyChainVerifier verifier;
    private static final Map<Path, SignatureMetadata> signatures = new HashMap<>();
    private static Path remoteProbeScript;

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        if (args[0].equals("--restart")) {
            verifyRestart(Path.of(args[1]));
            return;
        }
        root = Files.createTempDirectory("typed-services-runtime-");
        System.out.println("ISOLATED_RUNTIME=" + root);
        Path examples = Path.of(args[0]);
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, root.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, root.resolve("state").toString());
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve("data").toString());
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, plugins.toString());
        System.setProperty("pixivdownload.plugin-dev.enabled", "false");
        PluginSignatureTool.main(new String[]{"keygen", "--directory", root.resolve("keys").toString()});
        var key = new TrustedPluginKey("typed-service-test", SignatureMetadata.ED25519,
                SigningKeyFiles.publicKey(root.resolve("keys/public-key.pem")),
                TrustedPluginKey.State.ACTIVE, "Isolated acceptance", "Isolated acceptance", true);
        verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        Path contract = examples.resolve("contract/target/text-metrics-contract-0.1.0.jar");
        Path provider = examples.resolve("provider/target/text-metrics-provider-0.1.0.jar");
        Path consumer = examples.resolve("consumer/target/text-metrics-consumer-0.1.0.jar");
        Path fixtures = Path.of(args[3]);
        remoteProbeScript = Path.of(args[4]);
        Path missing = root.resolve("missing-contract");
        try (var installer = new ExternalPluginInstaller(missing, PluginPackageLimits.defaults(), verifier)) {
            check(installer.recoverPendingTransactions().safeToScan(), "missing-contract recovery");
            install(installer, consumer, false);
        }
        try (var session = PluginBootstrapSession.createContext(missing, PluginEnabledSnapshot.empty(), ignored -> verifier).start()) {
            check(session.manager().loadedDescriptor("text-metrics-consumer").isEmpty(), "required contract absence");
            check(session.status().hasFailures(), "required contract diagnostic");
        }
        try (var installer = new ExternalPluginInstaller(plugins, PluginPackageLimits.defaults(), verifier)) {
            check(installer.recoverPendingTransactions().safeToScan(), "initial recovery");
            install(installer, Path.of(args[1]), true);
            install(installer, Path.of(args[2]), true);
            install(installer, contract, false);
            install(installer, consumer, false);
            install(installer, fixtures.resolve("probe-contract.jar"), false);
            install(installer, fixtures.resolve("probe-provider.jar"), false);
            install(installer, fixtures.resolve("probe-consumer.jar"), false);
            install(installer, fixtures.resolve("remote-ledger-contract.jar"), false);
            install(installer, fixtures.resolve("remote-ledger-consumer.jar"), false);
        }
        var app = new SpringApplication(PixivDownloadApplication.class);
        app.addInitializers(context -> {
            var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(), ignored -> verifier);
            session.start();
            session.releaseStartupSnapshot();
            context.getBeanFactory().registerSingleton("pluginBootstrapSessionHandoff", new PluginBootstrapSessionHandoff(session));
            context.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent refreshed
                        && refreshed.getApplicationContext() == context) {
                    session.updateVerifierResolver(ignored -> verifier);
                }
            });
        });
        try (ConfigurableApplicationContext context = app.run("--server.port=0", "--server.address=127.0.0.1",
                "--setup.browser.auto-open=false", "--spring.main.banner-mode=off")) {
            var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
            var contexts = context.getBean(ExternalPluginContextManager.class);
            var setup = context.getBean(SetupService.class);
            setup.init("acceptance", "Isolated-acceptance-password-2026", "solo");
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            var client = HttpClient.newHttpClient();
            var denied = post(client, origin, "/api/text-metrics", "{\"texts\":[\"abc\"]}", null);
            check(denied.statusCode() == 401 || denied.statusCode() == 403, "anonymous rejected: " + denied.statusCode());
            var login = post(client, origin, "/api/auth/login",
                    "{\"username\":\"acceptance\",\"password\":\"Isolated-acceptance-password-2026\"}", null);
            check(login.statusCode() == 200, "login: " + login.statusCode());
            String cookie = login.headers().allValues("set-cookie").stream()
                    .filter(value -> value.startsWith("pixiv_session=")).findFirst().orElseThrow().split(";")[0];
            check(contexts.contextFor("download-workbench").orElseThrow().isActive(), "required workbench context active");
            var workbenchPage = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(URI.create(origin + "/pixiv-batch.html"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(workbenchPage.statusCode() == 200 && workbenchPage.body().contains("<html"),
                    "workbench page from installed binary: status=" + workbenchPage.statusCode()
                            + ", type=" + workbenchPage.headers().firstValue("Content-Type")
                            + ", location=" + workbenchPage.headers().firstValue("Location"));
            checkLocalizedUnavailable(client, origin, cookie);
            rejectPackagedCopies(coordinator, provider, examples.resolve("api/target/text-metrics-api-0.1.0.jar"));
            var pending = coordinator.installOrUpdate(provider, false, origin(provider, false, null));
            check(pending.installResult().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED, "provider trust: " + pending);
            var active = coordinator.installOrUpdate(provider, false, origin(provider, false,
                    pending.installResult().trustRequirement().artifactSha256()));
            check(active.activated(), "provider activated: " + active);
            var response = post(client, origin, "/api/text-metrics", "{\"texts\":[\"abc\",\"你好\"]}", cookie);
            check(response.statusCode() == 200 && JSON.readTree(response.body()).get("total").intValue() == 5,
                    "DTO HTTP round trip: " + response.statusCode() + " " + response.body());
            var child = contexts.contextFor("text-metrics-consumer").orElseThrow();
            ClassLoader loader = child.getBean("metricsController").getClass().getClassLoader();
            Class<?> api = loader.loadClass("com.example.textmetrics.api.TextMetrics");
            Class<?> inputType = loader.loadClass("com.example.textmetrics.api.TextMetrics$Request");
            Object input = inputType.getConstructor(List.class).newInstance(List.of("abc"));
            ServiceKey<?> serviceKey = new ServiceKey<>("text-metrics-contract", api);
            PluginServices access = child.getBean(PluginServices.class);
            Object oldProxy = access.require(serviceKey);
            check(api == contexts.contextFor("text-metrics-provider").orElseThrow()
                    .getBean("textMetrics").getClass().getInterfaces()[0], "single runtime Class");
            coordinator.stop("text-metrics-provider");
            checkLocalizedUnavailable(client, origin, cookie);
            rejected(api, inputType, oldProxy, input, ServiceAccessException.Reason.UNAVAILABLE);
            coordinator.start("text-metrics-provider");
            rejected(api, inputType, oldProxy, input, ServiceAccessException.Reason.UNAVAILABLE);
            Object restarted = access.require(serviceKey);
            api.getMethod("analyze", inputType).invoke(restarted, input);
            coordinator.reload("text-metrics-provider");
            rejected(api, inputType, restarted, input, ServiceAccessException.Reason.UNAVAILABLE);
            Object beforeConsumerStop = access.require(serviceKey);
            coordinator.stop("text-metrics-consumer");
            rejected(api, inputType, beforeConsumerStop, input, ServiceAccessException.Reason.NOT_ACTIVE);
            check(!child.isActive(), "consumer child closed");
            coordinator.start("text-metrics-consumer");
            response = post(client, origin, "/api/text-metrics", "{\"texts\":[\"ok\"]}", cookie);
            check(response.statusCode() == 200, "consumer restart");
            check(context.getBean(PluginRuntimeManager.class).loadedDescriptor("text-metrics-contract").isPresent(), "contract retained");
            exerciseFaults(context, fixtures);
            exerciseRemote(context, fixtures, origin, cookie);
            var contractEntry = context.getBean(top.sywyar.pixivdownload.plugin.registry.PluginRegistry.class)
                    .registeredPlugins().stream().filter(item -> item.id().equals("text-metrics-contract")).findFirst().orElseThrow();
            ClassLoader resident = contractEntry.classLoader();
            check(resident.getResource("contract-marker.txt") == null, "old contract bytes");
            Path updated = root.resolve("updated-contract.jar");
            try (var inputZip = new java.util.zip.ZipFile(contract.toFile());
                 var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(updated))) {
                var entries = inputZip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    try (var inputStream = inputZip.getInputStream(entry)) {
                        if (entry.getName().equals("plugin.properties")) {
                            String properties = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            out.write(properties.replace("plugin.version=0.1.0", "plugin.version=0.1.1")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } else inputStream.transferTo(out);
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new java.util.zip.ZipEntry("contract-marker.txt"));
                out.write("updated".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            var pendingUpdate = coordinator.installOrUpdate(updated, false, origin(updated, false, null));
            // 已批准发布者的等权限更新沿用正常信任策略；若要求确认则绑定本次候选摘要。
            var update = pendingUpdate.installResult().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED
                    ? coordinator.installOrUpdate(updated, false, origin(updated, false, pendingUpdate.installResult().trustRequirement().artifactSha256()))
                    : pendingUpdate;
            check(update.installResult().outcome() == PluginInstallOutcome.UPGRADED, "contract update installed: " + update);
            check(!update.activated() && !update.rolledBack() && update.installResult().installedPath() != null,
                    "contract disk update awaits restart: " + update);
            check(resident.getResource("contract-marker.txt") == null, "resident contract remains old");
            try {
                context.getBean(PluginRuntimeManager.class).unloadPlugin("text-metrics-contract");
                throw new AssertionError("contract loader unloaded");
            } catch (top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException expected) { }
            System.out.println("PASS signed-install, admin-HTTP, provider-absence, DTO, unique-Class, stop/start/reload, old-proxy, consumer-stop");
        }
        Process restarted = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                TypedServicesAcceptance.class.getName(), "--restart", root.toString()).inheritIO().start();
        check(restarted.waitFor() == 0, "restarted process");
    }

    private static void verifyRestart(Path runtime) throws Exception {
        var key = new TrustedPluginKey("typed-service-test", SignatureMetadata.ED25519,
                SigningKeyFiles.publicKey(runtime.resolve("keys/public-key.pem")),
                TrustedPluginKey.State.ACTIVE, "Isolated acceptance", "Isolated acceptance", true);
        var verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        System.setProperty("pixivdownload.plugin-dev.enabled", "false");
        try (var session = PluginBootstrapSession.createContext(runtime.resolve("plugins"), PluginEnabledSnapshot.empty(), ignored -> verifier).start()) {
            var contract = session.startupDiscovery().discovered().stream()
                    .filter(item -> item.sourcePluginId().equals("text-metrics-contract")).findFirst().orElseThrow();
            check(contract.classLoader().getResource("contract-marker.txt") != null, "new contract bytes after process restart");
            System.out.println("PASS contract disk/active separation and actual process restart pid=" + ProcessHandle.current().pid());
        }
    }

    private static void rejectPackagedCopies(ExternalPluginLifecycleCoordinator coordinator, Path provider, Path api) throws Exception {
        byte[] type;
        try (var jar = new java.util.zip.ZipFile(api.toFile());
             var input = jar.getInputStream(jar.getEntry("com/example/textmetrics/api/TextMetrics.class"))) {
            type = input.readAllBytes();
        }
        var additions = List.of(
                Map.of("com/example/textmetrics/api/TextMetrics.class", type),
                Map.of("META-INF/versions/17/com/example/textmetrics/api/TextMetrics.class", type),
                Map.of("lib/copied-api.jar", Files.readAllBytes(api)),
                Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nClass-Path: missing.jar\r\n\r\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (int i = 0; i < additions.size(); i++) {
            Path duplicate = root.resolve("duplicate-" + i + ".jar");
            Map<String, byte[]> extra = additions.get(i);
            try (var jar = new java.util.zip.ZipFile(provider.toFile());
                 var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(duplicate))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (extra.containsKey(entry.getName())) continue;
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    try (var input = jar.getInputStream(entry)) { input.transferTo(out); }
                    out.closeEntry();
                }
                for (var entry : extra.entrySet()) {
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
            var result = coordinator.installOrUpdate(duplicate, false, origin(duplicate, false, null));
            check(result.installResult().outcome() == PluginInstallOutcome.REJECTED_UNSAFE && !result.activated(),
                    "duplicate/implicit class path rejected before activation: " + result);
        }
        System.out.println("PASS signed root/multi-release/nested copies and implicit classpath rejected before activation");
    }

    private static void exerciseFaults(ConfigurableApplicationContext context, Path fixtures) throws Exception {
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var contexts = context.getBean(ExternalPluginContextManager.class);
        var states = context.getBean(top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState.class);
        var q = contexts.contextFor("probe-consumer").orElseThrow();
        var p = contexts.contextFor("probe-provider").orElseThrow();
        ClassLoader loader = p.getBean("forward").getClass().getClassLoader();
        Class<?> control = loader.loadClass("acceptance.participant.Participant");
        check("NOT_ACTIVE".equals(control.getField("initialReason").get(null)), "constructor access rejected");
        Class<?> api = loader.loadClass("acceptance.contract.Probe$Forward");
        Class<?> requestType = loader.loadClass("acceptance.contract.Probe$Request");
        var access = q.getBean(PluginServices.class);
        var key = new ServiceKey<>("probe-contract", api);
        Object proxy = access.require(key);
        byte[] inputBytes = {1};
        Object request = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("echo", 0, inputBytes);
        Object reply = call(api, requestType, proxy, request);
        byte[] output = (byte[]) reply.getClass().getMethod("data").invoke(reply);
        control.getMethod("corrupt").invoke(null);
        check(inputBytes[0] == 1 && output[0] == 7, "two-way copies");
        request = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("nested", 3, inputBytes);
        reply = call(api, requestType, proxy, request);
        check(reply.getClass().getMethod("value").invoke(reply).equals("nested-done"), "A-B-A nested calls");
        Object ordinary = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("ordinary", 0, inputBytes);
        try { call(api, requestType, proxy, ordinary); throw new AssertionError("ordinary accepted"); }
        catch (ServiceAccessException expected) { check(expected.getCause() == null && expected.getSuppressed().length == 0, "exception graph"); }
        Object fatal = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("fatal", 0, inputBytes);
        try { call(api, requestType, proxy, fatal); throw new AssertionError("fatal accepted"); }
        catch (InternalError expected) { check(expected.getMessage().equals("test fatal"), "fatal preserved"); }
        Runnable stopInsideCall = () -> coordinator.stop("probe-consumer");
        control.getMethod("hook", Runnable.class).invoke(null, stopInsideCall);
        Object hook = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("hook", 0, inputBytes);
        try { call(api, requestType, proxy, hook); throw new AssertionError("synchronous stop accepted"); }
        catch (ServiceAccessException expected) { check(q.isActive() && states.acceptsNewRequests("probe-consumer"), "sync stop leaves active context"); }
        finally { control.getMethod("hook", Runnable.class).invoke(null, new Object[]{null}); }

        Path second = fixtures.resolve("probe-provider-two.jar");
        activate(coordinator, second);
        try { access.require(key); throw new AssertionError("ambiguity accepted"); }
        catch (ServiceAccessException expected) { check(expected.reason() == ServiceAccessException.Reason.AMBIGUOUS, "ambiguity reason"); }
        coordinator.stop("probe-provider-two");
        coordinator.unload("probe-provider-two");
        Path broken = fixtures.resolve("probe-provider-broken.jar");
        var pending = coordinator.installOrUpdate(broken, false, origin(broken, false, null));
        check(pending.installResult().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED, "broken trust");
        var failed = coordinator.installOrUpdate(broken, false, origin(broken, false, pending.installResult().trustRequirement().artifactSha256()));
        check(!failed.activated() && contexts.contextFor("probe-provider-broken").isEmpty(), "prepare rollback context");
        check(access.find(key).isPresent(), "prepare rollback removed failed provider");

        Object blocking = requestType.getConstructor(String.class, int.class, byte[].class).newInstance("block", 0, inputBytes);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread calling = new Thread(() -> { try { call(api, requestType, proxy, blocking); } catch (Throwable error) { failure.set(error); } }, "acceptance-blocked-call");
        calling.start();
        var entered = (java.util.concurrent.CountDownLatch) control.getField("entered").get(null);
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "call entered");
        var stopFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread stopping = new Thread(() -> { try { coordinator.stop("probe-consumer"); } catch (Throwable error) { stopFailure.set(error); } }, "acceptance-stop-consumer");
        stopping.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (states.acceptsNewRequests("probe-consumer") && System.nanoTime() < deadline) Thread.sleep(10);
        try {
            check(!states.acceptsNewRequests("probe-consumer") && q.isActive(), "consumer quiesced but context retained");
            calling.interrupt();
            check(calling.isAlive() && p.isActive() && q.isActive(), "ignored cancellation retains owners");
            try { call(api, requestType, proxy, ordinary); throw new AssertionError("quiesced call accepted"); }
            catch (ServiceAccessException expected) { check(expected.reason() == ServiceAccessException.Reason.NOT_ACTIVE, "consumer admission"); }
        } finally {
            control.getMethod("release").invoke(null);
            calling.join(10_000);
            stopping.join(10_000);
        }
        check(!calling.isAlive() && !stopping.isAlive() && failure.get() == null && stopFailure.get() == null, "actual drain exits");
        check(!q.isActive() && p.isActive(), "context closed only after drain");
        coordinator.start("probe-consumer");
        var currentQ = contexts.contextFor("probe-consumer").orElseThrow();
        Object beforeReplace = currentQ.getBean(PluginServices.class).require(key);
        control.getMethod("reset").invoke(null);
        Thread replacementCall = new Thread(() -> { try { call(api, requestType, beforeReplace, blocking); } catch (Throwable error) { failure.set(error); } }, "acceptance-replacement-call");
        replacementCall.start();
        entered = (java.util.concurrent.CountDownLatch) control.getField("entered").get(null);
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "replacement call entered");
        stopFailure.set(null);
        Thread replacement = new Thread(() -> { try { coordinator.reload("probe-provider"); } catch (Throwable error) { stopFailure.set(error); } }, "acceptance-replace-provider");
        replacement.start();
        try {
            replacementCall.interrupt();
            // reload 失败后还会尝试清退同一代并回滚，两次 drain 都必须保留在途租约。
            replacement.join(65_000);
            check(!replacement.isAlive() && stopFailure.get() != null && replacementCall.isAlive(),
                    "drain timeout is not completion: replacementAlive=" + replacement.isAlive()
                            + ", failure=" + stopFailure.get() + ", callAlive=" + replacementCall.isAlive());
            check(p.isActive() && !states.acceptsNewRequests("probe-provider"), "timed-out replacement keeps quiesced context");
        } finally {
            control.getMethod("release").invoke(null);
            replacementCall.join(10_000);
            replacement.join(10_000);
        }
        check(!replacementCall.isAlive() && failure.get() == null, "replacement call exits");
        coordinator.reload("probe-provider");
        check(!p.isActive(), "provider context release");
        try { call(api, requestType, beforeReplace, ordinary); throw new AssertionError("replaced proxy accepted"); }
        catch (ServiceAccessException expected) { check(expected.reason() == ServiceAccessException.Reason.UNAVAILABLE, "replaced publication"); }
        coordinator.stop("probe-provider");
        coordinator.unload("probe-provider");
        System.out.println("PASS required-contract-absence, activation-timing, copies, A-B-A, ambiguity, rollback, ordinary/fatal, sync-stop, blocked-drain");
    }

    private static Object call(Class<?> api, Class<?> type, Object proxy, Object request) throws Exception {
        try { return api.getMethod("call", type).invoke(proxy, request); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static void activate(ExternalPluginLifecycleCoordinator coordinator, Path jar) throws Exception {
        var pending = coordinator.installOrUpdate(jar, false, origin(jar, false, null));
        check(pending.installResult().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED, "trust: " + pending);
        var active = coordinator.installOrUpdate(jar, false, origin(jar, false, pending.installResult().trustRequirement().artifactSha256()));
        check(active.activated(), "activation: " + active);
    }

    private static HttpResponse<String> post(HttpClient client, String origin, String path, String body, String cookie) throws Exception {
        return post(client, origin, path, body, cookie, "en-US");
    }


    private static void exerciseRemote(ConfigurableApplicationContext context, Path fixtures, String origin, String cookie) throws Exception {
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var contexts = context.getBean(ExternalPluginContextManager.class);
        var consumer = contexts.contextFor("remote-ledger-consumer").orElseThrow();
        ServiceBinding binding = consumer.getBean(ServiceBinding.class);
        var invites = context.getBean(top.sywyar.pixivdownload.setup.guest.GuestInviteService.class);
        var invitation = new top.sywyar.pixivdownload.setup.guest.dto.InviteCreateRequest();
        invitation.setName("Isolated remote permission probe");
        long invitationId = invites.createInvite(invitation);
        String guest = top.sywyar.pixivdownload.setup.AuthFilter.INVITE_COOKIE + "=" + invites.detail(invitationId).code();
        remoteProbe(context, fixtures, origin, cookie, guest, false);
        activate(coordinator, fixtures.resolve("remote-ledger-provider.jar"));
        await(() -> binding.state() == ServiceBinding.State.ACTIVE, "remote binding active");
        remoteProbe(context, fixtures, origin, cookie, guest, true);

        var provider = contexts.contextFor("remote-ledger-provider").orElseThrow();
        Class<?> control = provider.getClassLoader().loadClass("acceptance.remote.RemoteParticipant");
        Class<?> api = consumer.getClassLoader().loadClass("acceptance.remotecontract.RemoteLedger");
        String identity;
        try (var input = api.getResourceAsStream("/META-INF/pixivdownload/schema/acceptance.remotecontract.RemoteLedger.remote.json")) {
            identity = JSON.readTree(input).path("identity").asText();
        }
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/remote-ledger/record"))
                .header("Origin", origin).header("Cookie", cookie).header("Content-Type", "application/json")
                .header("X-Pixiv-Contract", identity).POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sequence\":1,\"text\":\"wait\",\"numbers\":[],\"labels\":{},\"mode\":\"BLOCK\"}")).build();
        var calling = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var entered = (java.util.concurrent.CountDownLatch) control.getField("entered").get(null);
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "remote entered");
            try { coordinator.stop("remote-ledger-provider"); }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) { }
            check(provider.isActive(), "remote provider retained during actual invocation");
            calling.cancel(true);
            check(provider.isActive(), "client cancellation is not server drain");
        } finally { control.getMethod("release").invoke(null); }
        stopEventually(coordinator, "remote-ledger-provider");
        check(!provider.isActive(), "remote provider closes after real exit");
        await(() -> binding.state() == ServiceBinding.State.WAITING, "remote binding absent");
        remoteProbe(context, fixtures, origin, cookie, guest, false);
        coordinator.start("remote-ledger-provider");
        await(() -> binding.state() == ServiceBinding.State.ACTIVE, "remote binding restored");
        remoteProbe(context, fixtures, origin, cookie, guest, true);

        stopEventually(coordinator, "remote-ledger-consumer");
        check(binding.state() == ServiceBinding.State.CLOSED && !consumer.isActive(), "remote consumer resources released");
        stopEventually(coordinator, "remote-ledger-provider");
        coordinator.start("remote-ledger-provider");
        check(binding.state() == ServiceBinding.State.CLOSED, "stopped consumer cannot revive");
        coordinator.start("remote-ledger-consumer");
        remoteProbe(context, fixtures, origin, cookie, guest, true);
        ClassLoader beforeReplacement = contexts.contextFor("remote-ledger-provider").orElseThrow().getClassLoader();
        stopEventually(coordinator, "remote-ledger-provider");
        coordinator.reload("remote-ledger-provider");
        check(beforeReplacement != contexts.contextFor("remote-ledger-provider").orElseThrow().getClassLoader(),
                "remote physical replacement uses a new classloader");
        var replacementBinding = contexts.contextFor("remote-ledger-consumer").orElseThrow().getBean(ServiceBinding.class);
        await(() -> replacementBinding.state() == ServiceBinding.State.ACTIVE, "remote binding after physical replacement");
        remoteProbe(context, fixtures, origin, cookie, guest, true);
        System.out.println("REMOTE_FROZEN_HOST_ACCEPTANCE_PASSED");
    }

    private static void remoteProbe(ConfigurableApplicationContext context, Path fixtures, String origin, String cookie, String guest, boolean active) throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = null;
        int before = 0;
        if (active) {
            var provider = context.getBean(ExternalPluginContextManager.class).contextFor("remote-ledger-provider").orElseThrow();
            calls = (java.util.concurrent.atomic.AtomicInteger) provider.getClassLoader()
                    .loadClass("acceptance.remote.RemoteParticipant").getField("calls").get(null);
            before = calls.get();
        }
        Path module = fixtures.getParent().resolve("remote-consumer/target/classes/web/generated/acceptance.remotecontract.RemoteLedger.mjs");
        var builder = new ProcessBuilder("node", remoteProbeScript.toString(), module.toString(), active ? "active" : "absent");
        builder.environment().put("REMOTE_ORIGIN", origin);
        builder.environment().put("REMOTE_COOKIE", cookie);
        builder.environment().put("REMOTE_GUEST", guest);
        Process process = builder.inheritIO().start();
        check(process.waitFor() == 0, "generated frontend actual HTTP probe");
        if (active) check(calls.get() - before == 5, "only three valid requests and two explicit failure probes reach provider");
    }

    private static void stopEventually(ExternalPluginLifecycleCoordinator coordinator, String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { coordinator.stop(id); return; }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) {
                if (System.nanoTime() >= deadline) throw pending;
                Thread.sleep(10);
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean(), label);
    }

    private static void checkLocalizedUnavailable(HttpClient client, String origin, String cookie) throws Exception {
        String previous = null;
        for (String language : List.of("zh-CN", "en-US")) {
            var response = post(client, origin, "/api/text-metrics", "{\"texts\":[\"abc\"]}", cookie, language);
            check(response.statusCode() == 503, "service unavailable status");
            var body = JSON.readTree(response.body());
            check(body.size() == 2 && body.path("code").asText().equals("UNAVAILABLE"), "stable error code");
            String error = body.path("error").asText();
            check(!error.isBlank() && !error.equals("service.unavailable"), "localized error field");
            if (previous != null) check(!previous.equals(error), "request language changes error only");
            previous = error;
        }
    }

    private static HttpResponse<String> post(HttpClient client, String origin, String path, String body, String cookie,
                                           String language) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Origin", origin)
                .header("Accept-Language", language)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void rejected(Class<?> api, Class<?> inputType, Object proxy, Object input, ServiceAccessException.Reason reason) throws Exception {
        try {
            api.getMethod("analyze", inputType).invoke(proxy, input);
            throw new AssertionError("stale proxy accepted");
        } catch (InvocationTargetException failure) {
            check(failure.getCause() instanceof ServiceAccessException access && access.reason() == reason,
                    "proxy rejection: " + failure.getCause());
        }
    }

    private static PluginPackageOrigin origin(Path jar, boolean official, String confirmation) throws Exception {
        SignatureMetadata signature = signatures.get(jar);
        if (signature == null) {
            var descriptor = PluginPackageReader.inspect(jar).descriptor();
            Path out = root.resolve(descriptor.id() + ".sig");
            PluginSignatureTool.main(new String[]{"artifact", "--artifact", jar.toString(),
                    "--plugin-id", descriptor.id(), "--version", descriptor.version(), "--key-id", "typed-service-test",
                    "--private-key", root.resolve("keys/private-key.pem").toString(), "--out", out.toString()});
            signature = JSON.readValue(out.toFile(), SignatureMetadata.class);
            signatures.put(jar, signature);
        }
        return PluginPackageOrigin.forTrustedCatalog("isolated-acceptance", official,
                Files.size(jar), PluginPackageIntegrity.sha256Hex(jar), signature, Map.of(), confirmation);
    }

    private static void install(ExternalPluginInstaller installer, Path jar, boolean official) throws Exception {
        var pending = installer.prepareTransaction(jar, false, origin(jar, official, null));
        if (pending.result().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED) {
            pending = installer.prepareTransaction(jar, false, origin(jar, official, pending.result().trustRequirement().artifactSha256()));
        } else check(official, "custom package requires explicit trust");
        check(pending.readyToCommit(), "install ready: " + pending.result());
        var committed = installer.commitTransaction(pending);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);
        installer.completeTransaction(committed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
