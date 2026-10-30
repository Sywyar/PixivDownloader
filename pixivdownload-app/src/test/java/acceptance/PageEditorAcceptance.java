package acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.*;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.*;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.*;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.SigningKeyFiles;
import top.sywyar.pixivdownload.setup.SetupService;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 固定宿主产物上的独立插件安装、真实 HTTP 和浏览器验收，不把业务类放入宿主 classpath。 */
public final class PageEditorAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path root;
    private static final Map<Path, SignatureMetadata> signatures = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Path fixed = Path.of(args[0]); root = fixed.resolve("runtime"); Files.createDirectory(root);
        Path plugins = root.resolve("plugins"); Files.createDirectory(plugins);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, root.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, root.resolve("state").toString());
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve("data").toString());
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, plugins.toString());
        System.setProperty("pixivdownload.plugin-dev.enabled", "false");
        PluginSignatureTool.main(new String[]{"keygen", "--directory", root.resolve("keys").toString()});
        var key = new TrustedPluginKey("page-test", SignatureMetadata.ED25519,
                SigningKeyFiles.publicKey(root.resolve("keys/public-key.pem")), TrustedPluginKey.State.ACTIVE,
                "Isolated page acceptance", "Isolated page acceptance", true);
        var verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        try (var installer = new ExternalPluginInstaller(plugins, PluginPackageLimits.defaults(), verifier)) {
            check(installer.recoverPendingTransactions().safeToScan(), "initial recovery");
            for (String id : List.of("database-sqlite", "download-workbench", "page-editor", "ai", "tts"))
                install(installer, fixed.resolve(id + ".jar"), true);
            install(installer, fixed.resolve("fixture/page-checklist-contract.jar"), false);
        }
        new top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor(RuntimeFiles.resolvePluginConfigPath("ai", "properties"))
                .writeAll(Map.of("ai.enabled", "true", "ai.base-url", "http://127.0.0.1:9", "ai.model", "fixture-model"));
        new top.sywyar.pixivdownload.config.credential.PluginCredentialStore().update("ai", Map.of("ai.api-key", "neutral-browser-key"));
        var app = new SpringApplication(PixivDownloadApplication.class);
        app.addInitializers(context -> {
            var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(), ignored -> verifier);
            session.start(); session.releaseStartupSnapshot();
            context.getBeanFactory().registerSingleton("pluginBootstrapSessionHandoff", new PluginBootstrapSessionHandoff(session));
            context.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent refreshed
                        && refreshed.getApplicationContext() == context) session.updateVerifierResolver(ignored -> verifier);
            });
        });
        try (ConfigurableApplicationContext context = app.run("--server.port=0", "--server.address=127.0.0.1",
                "--setup.browser.auto-open=false", "--update.auto-check=false", "--plugin-catalog.enabled=false",
                "--spring.main.banner-mode=off")) {
            context.getBean(SetupService.class).init("acceptance", "Isolated-page-password-2026", "solo");
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            var login = request(origin, "/api/auth/login", "{\"username\":\"acceptance\",\"password\":\"Isolated-page-password-2026\"}", null);
            check(login.statusCode() == 200, "login");
            String cookie = login.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("pixiv_session="))
                    .findFirst().orElseThrow().split(";")[0];
            var aiStatus = request(origin, "/api/admin/ai/status", null, cookie);
            check(aiStatus.statusCode() == 200 && JSON.readTree(aiStatus.body()).path("configured").asBoolean(),
                    "isolated AI fixture configured without external requests");
            var contexts = context.getBean(ExternalPluginContextManager.class);
            check(contexts.contextFor("page-editor").isPresent(), "page editor started");
            check(request(origin, "/api/page-editor/catalog", null, null).statusCode() == 401, "anonymous catalog rejected");
            check(request(origin, "/api/page-editor/documents/core:login", null, null).statusCode() == 401, "anonymous draft rejected");
            String cssPath = "/api/page-editor/styles/parse";
            String declarations = "{\"css\":\"padding: 16px;\"}";
            check(request(origin, cssPath, declarations, null).statusCode() == 401, "anonymous CSS validation rejected");
            check(request(origin, cssPath, declarations, cookie).statusCode() == 200, "administrator CSS validation");
            check(request(origin, cssPath, "{\"css\":\"position:fixed\"}", cookie).statusCode() == 422, "unsupported CSS rejected on server");
            var crossSite = HttpRequest.newBuilder(URI.create(origin + cssPath)).header("Cookie", cookie)
                    .header("Origin", "https://example.test").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(declarations)).build();
            check(HttpClient.newHttpClient().send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "CSS validation enforces CSRF");
            check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 404, "no publication");
            browser(args[1], origin, cookie, "unchanged", fixed);
            check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 404, "unchanged publication uses owner default");
            Path incompleteFile = root.resolve("data/page-editor/pages/core_login.json");
            byte[] completeState = Files.readAllBytes(incompleteFile);
            try {
                var incomplete = JSON.readTree(completeState);
                var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) incomplete.path("draft").path("project")
                        .path("pages").get(0).path("frames").get(0).path("component");
                wrapper.putArray("components").addObject().put("type", "page-region").put("region", "authentication");
                byte[] damaged = JSON.writeValueAsBytes(incomplete);
                Files.write(incompleteFile, damaged);
                browser(args[1], origin, cookie, "incomplete", fixed);
                check(Arrays.equals(damaged, Files.readAllBytes(incompleteFile)), "recovery preview never rewrites the saved draft");
            } finally { Files.write(incompleteFile, completeState); }
            var catalog = context.getBean(PageCatalog.class);
            check(catalog.components().isEmpty(), "fixed editor has no knowledge of fixture");
            var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
            Process live = startBrowser(args[1], origin, cookie, "live", fixed);
            awaitFile(fixed.resolve("live-ready"), live);
            Path sample = fixed.resolve("fixture/page-checklist.jar");
            var pending = coordinator.installOrUpdate(sample, false, origin(sample, false, null));
            check(pending.installResult().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED, "independent plugin needs trust");
            var active = coordinator.installOrUpdate(sample, false, origin(sample, false, pending.installResult().trustRequirement().artifactSha256()));
            check(active.activated(), "independent plugin activated: " + active);
            check(catalog.components().size() == 1, "component discovered");
            awaitFile(fixed.resolve("live-discovered"), live);
            coordinator.stop("page-checklist");
            awaitFile(fixed.resolve("live-withdrawn"), live);
            coordinator.start("page-checklist");
            check(live.waitFor() == 0, "live discovery and withdrawal");
            Process slots = startBrowser(Path.of(args[1]).resolveSibling("ui-slot-browser.mjs").toString(), origin, cookie, "slots", fixed);
            awaitFile(fixed.resolve("slots-ready"), slots);
            coordinator.stop("page-checklist");
            awaitFile(fixed.resolve("slots-withdrawn"), slots);
            coordinator.start("page-checklist");
            check(slots.waitFor() == 0, "ordinary UiSlot browser lifecycle");
            Process officialSlots = startBrowser(Path.of(args[1]).resolveSibling("official-slot-browser.mjs").toString(),
                    origin, cookie, "official", fixed);
            awaitFile(fixed.resolve("official-slots-loading"), officialSlots);
            coordinator.stop("ai");
            coordinator.stop("tts");
            awaitFile(fixed.resolve("official-slots-withdrawn"), officialSlots);
            coordinator.start("ai");
            coordinator.start("tts");
            check(officialSlots.waitFor() == 0, "real AI and TTS browser lifecycle");
            String oldPublication = catalog.components().get(0).publication();
            browser(args[1], origin, cookie, "active", fixed);
            var publicView = request(origin, "/api/web/page-publications/core:login", null, null);
            check(publicView.statusCode() == 200 && !publicView.body().contains("propertiesModuleUrl")
                    && !publicView.body().contains("\"configurationSchema\"") && !publicView.body().contains("\"draft\""), "public projection only");
            var publicPorts = JSON.readTree(publicView.body()).findValues("inputs");
            check(!publicPorts.isEmpty() && publicPorts.stream().allMatch(ports -> ports.isArray()
                    && ports.path(0).path("contractId").isTextual() && ports.path(0).path("schema").isTextual()),
                    "public ports carry only their explicit value contracts");
            var head = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/api/web/page-publications/core:login"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            check(head.statusCode() == 200 && head.body().isEmpty(), "publication liveness does not download the document");
            var original = request(origin, "/api/page-editor/documents/core:login", null, cookie);
            var state = JSON.readTree(original.body());
            var unsafe = state.path("draft").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.path("project")).put("scripts", "alert(1)");
            var invalid = JSON.createObjectNode().put("revision", state.path("revision").asLong()); invalid.set("document", unsafe);
            check(request(origin, "/api/page-editor/documents/core:login/save", invalid.toString(), cookie).statusCode() == 422, "engine script rejected");
            check(request(origin, "/api/page-editor/documents/core:login/publish", "{\"revision\":0}", cookie).statusCode() == 409, "concurrent publish rejected");
            check(request(origin, "/api/page-editor/documents/core:login", null, cookie).body().equals(original.body()), "failed changes preserve document");
            var componentClass = contexts.contextFor("page-checklist").orElseThrow().getClassLoader()
                    .loadClass("example.pages.plugin.ChecklistPlugin");
            Path snapshot = Path.of(componentClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            coordinator.stop("page-checklist");
            coordinator.unload("page-checklist");
            check(!Files.exists(snapshot), "unload releases the served artifact snapshot");
            check(catalog.components().isEmpty(), "withdrawn catalog");
            check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 503, "missing component refuses publication");
            browser(args[1], origin, cookie, "missing", fixed);
            coordinator.load("page-checklist");
            coordinator.start("page-checklist");
            check(!catalog.components().get(0).publication().equals(oldPublication), "new publication after start");
            var stale = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/page-checklist/render/component.mjs?pagePublication=" + oldPublication))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(stale.statusCode() == 409, "old module publication rejected");
            browser(args[1], origin, cookie, "restored", fixed);
            Path pageFile = root.resolve("data/page-editor/pages/core_login.json");
            byte[] savedPage = Files.readAllBytes(pageFile);
            try {
                Files.writeString(pageFile, "{broken", java.nio.charset.StandardCharsets.UTF_8);
                check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 503, "corrupt source rejected");
                browser(args[1], origin, cookie, "corrupt", fixed);
            } finally { Files.write(pageFile, savedPage); }
            verifyDrain(context, coordinator, origin, cookie);
            coordinator.start("page-checklist");
            coordinator.stop("page-editor");
            check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 404, "editor absence withdraws source");
            browser(args[1], origin, cookie, "default", fixed);
            System.out.println("PAGE_EDITOR_SIGNED_INSTALL_HTTP_BROWSER_LIFECYCLE_PASSED");
        }
    }

    private static void verifyDrain(ConfigurableApplicationContext context, ExternalPluginLifecycleCoordinator coordinator,
                                    String origin, String cookie) throws Exception {
        var child = context.getBean(ExternalPluginContextManager.class).contextFor("page-checklist").orElseThrow();
        Class<?> control = child.getClassLoader().loadClass("example.pages.plugin.ChecklistPlugin");
        String identity;
        try (var input = child.getClassLoader().getResourceAsStream("META-INF/pixivdownload/schema/example.pages.contract.Checklist.remote.json")) {
            identity = JSON.readTree(input).path("identity").asText();
        }
        String publication = context.getBean(PageCatalog.class).components().get(0).publication();
        var failed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/api/page-checklist/verify"))
                .header("Origin", origin).header("Cookie", cookie).header("Content-Type", "application/json")
                .header("X-Pixiv-Contract", identity).header("X-Pixiv-Page-Publication", publication)
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"__fail\",\"items\":[]}")).build(), HttpResponse.BodyHandlers.ofString());
        check(failed.statusCode() >= 400, "ordinary business failure is not success");
        var call = HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(origin + "/api/page-checklist/verify"))
                .header("Origin", origin).header("Cookie", cookie).header("Content-Type", "application/json")
                .header("X-Pixiv-Contract", identity).header("X-Pixiv-Page-Publication", publication)
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"__block\",\"items\":[]}")).build(), HttpResponse.BodyHandlers.ofString());
        var entered = (CountDownLatch) control.getField("entered").get(null);
        var release = (CountDownLatch) control.getField("release").get(null);
        try {
            check(entered.await(5, TimeUnit.SECONDS), "business operation entered");
            try { coordinator.stop("page-checklist"); }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) { }
            check(child.isActive(), "in-flight business call retains owner");
            call.cancel(true);
            check(child.isActive(), "client cancellation does not fake server drain");
        } finally { release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (child.isActive()) {
            try { coordinator.stop("page-checklist"); }
            catch (top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException pending) {
                if (System.nanoTime() >= deadline) throw pending;
                Thread.sleep(10);
            }
        }
        check(!child.isActive(), "owner closes after operation exits");
    }
    private static void browser(String script, String origin, String cookie, String phase, Path fixed) throws Exception {
        check(startBrowser(script, origin, cookie, phase, fixed).waitFor() == 0, "browser " + phase);
    }
    private static Process startBrowser(String script, String origin, String cookie, String phase, Path fixed) throws Exception {
        var builder = new ProcessBuilder("node", script, phase, fixed.toString());
        builder.environment().put("PAGE_ORIGIN", origin); builder.environment().put("PAGE_COOKIE", cookie);
        return builder.inheritIO().start();
    }
    private static void awaitFile(Path file, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!Files.exists(file)) {
            check(process.isAlive() && System.nanoTime() < deadline, "browser signal " + file.getFileName());
            Thread.sleep(50);
        }
    }
    private static HttpResponse<String> request(String origin, String path, String body, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Accept-Language", "en-US");
        if (cookie != null) request.header("Cookie", cookie);
        if (body == null) request.GET();
        else request.header("Origin", origin).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static PluginPackageOrigin origin(Path jar, boolean official, String confirmation) throws Exception {
        SignatureMetadata signature = signatures.get(jar);
        if (signature == null) {
            var descriptor = PluginPackageReader.inspect(jar).descriptor(); Path out = root.resolve(descriptor.id() + ".sig");
            PluginSignatureTool.main(new String[]{"artifact", "--artifact", jar.toString(), "--plugin-id", descriptor.id(),
                    "--version", descriptor.version(), "--key-id", "page-test", "--private-key", root.resolve("keys/private-key.pem").toString(), "--out", out.toString()});
            signature = JSON.readValue(out.toFile(), SignatureMetadata.class); signatures.put(jar, signature);
        }
        return PluginPackageOrigin.forTrustedCatalog("isolated-pages", official, Files.size(jar),
                PluginPackageIntegrity.sha256Hex(jar), signature, Map.of(), confirmation);
    }
    private static void install(ExternalPluginInstaller installer, Path jar, boolean official) throws Exception {
        var pending = installer.prepareTransaction(jar, false, origin(jar, official, null));
        if (pending.result().outcome() == PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED)
            pending = installer.prepareTransaction(jar, false, origin(jar, official, pending.result().trustRequirement().artifactSha256()));
        check(pending.readyToCommit(), "install ready: " + pending.result());
        var committed = installer.commitTransaction(pending); installer.verifyCommittedTarget(committed);
        installer.markActivated(committed); installer.completeTransaction(committed);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
