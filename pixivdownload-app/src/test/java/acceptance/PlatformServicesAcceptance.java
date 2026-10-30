package acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.credential.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.lifecycle.*;
import static acceptance.TypedServicesAcceptance.*;

/** 在固定宿主之外构建的业务契约通过实际安装、HTTP、私库与生命周期验收。 */
public final class PlatformServicesAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PlatformServicesAcceptance() { }

    public static void run(ConfigurableApplicationContext context, Path fixtures, String origin, String cookie) throws Exception {
        try { exercise(context, fixtures, origin, cookie); }
        finally {
            var contexts = context.getBean(ExternalPluginContextManager.class);
            if(contexts.contextFor("independent-jobs").isPresent())
                ((CountDownLatch)control(contexts, "jobs").getField("release").get(null)).countDown();
        }
    }
    private static void exercise(ConfigurableApplicationContext context, Path fixtures, String origin, String cookie) throws Exception {
        try {
            Class.forName("acceptance.business.Business");
            throw new AssertionError("unknown business contract leaked into host classpath");
        } catch(ClassNotFoundException expected) { }
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var contexts = context.getBean(ExternalPluginContextManager.class);
        var client = HttpClient.newHttpClient();
        var collector = contexts.contextFor("independent-collector").orElseThrow();
        var binding = collector.getBean(ServiceBinding.class);
        await(() -> binding.state() == ServiceBinding.State.ACTIVE, "empty collection binds");
        var initialLoader = collector.getClassLoader();
        for(String id : List.of("storage", "validator-one", "validator-two", "sink-one", "sink-two", "jobs")) {
            activate(coordinator, fixtures.resolve("independent-" + id + ".jar"));
        }
        await(() -> binding.diagnostic().dependencies().stream().mapToInt(d -> d.providers().size()).sum() == 4,
            "collection observes two validators and two sinks");
        check(invoke(client, origin, cookie, "collect", "submit", "first").path("status").asText().equals("STORED"), "collector stores");
        check(invoke(client, origin, cookie, "display", "query", "").path("values").toString().equals("[\"first\"]"), "display queries private storage");
        for(String id : List.of("sink-one", "sink-two")) check(counter(contexts, id, "events") == 1, "both sinks receive event");
        check(invoke(client, origin, cookie, "collect", "submit", "reject-one").path("status").asText().equals("REJECTED"), "first validator rejects");
        activate(coordinator, fixtures.resolve("independent-validator-three.jar"));
        check(collector.getClassLoader() == initialLoader, "third implementation leaves collector binary and context intact");
        await(() -> binding.diagnostic().dependencies().stream().mapToInt(d -> d.providers().size()).sum() == 5,
            "collection binds third validator");
        check(invoke(client, origin, cookie, "collect", "submit", "reject-three").path("status").asText().equals("REJECTED"), "new validator participates");
        check(invoke(client, origin, cookie, "display", "query", "").path("values").size() == 1, "rejection precedes storage");
        control(contexts, "sink-one").getField("sinkFailure").setBoolean(null, true);
        var stored = invoke(client, origin, cookie, "collect", "submit", "second");
        check(stored.path("status").asText().equals("STORED") && stored.path("values").get(1).asText().equals("1"), "sink failure does not roll back storage");
        check(counter(contexts, "sink-two", "events") == 2, "broadcast continues after failing sink");

        Class<?> validator = collector.getClassLoader().loadClass("acceptance.business.Business$Validator");
        var key = new ServiceKey<>("independent-contract", validator);
        var access = collector.getBean(PluginServices.class);
        var retained = access.findAll(key);
        var oldMember = retained.stream().filter(member -> member.publication().pluginId().equals("independent-validator-three")).findFirst().orElseThrow();
        stopEventually(coordinator, "independent-validator-three");
        coordinator.start("independent-validator-three");
        Class<?> entry = collector.getClassLoader().loadClass("acceptance.business.Business$Entry");
        try {
            validator.getMethod("validate", entry).invoke(oldMember.service(), entry.getConstructor(String.class, String.class).newInstance("old", "entry"));
            throw new AssertionError("old collection member routed into replacement");
        } catch(java.lang.reflect.InvocationTargetException error) {
            check(error.getCause() instanceof ServiceAccessException failure && failure.reason() == ServiceAccessException.Reason.UNAVAILABLE, "old member unavailable");
        }
        check(retained.size() == 3, "discovery snapshot stays immutable");
        stopEventually(coordinator, "independent-storage");
        check(post(client, origin, "/api/independent/display", "{\"operation\":\"query\",\"value\":\"\"}", cookie).statusCode() == 503, "storage absence is not empty data");
        coordinator.start("independent-storage");
        check(invoke(client, origin, cookie, "display", "query", "").path("values").size() == 2, "private data survives stop/start");

        var credentials = collector.getBean(PluginCredentials.class);
        credentials.update(Map.of("independent-collector.token", "neutral-secret-fixture"));
        check(credentials.read("independent-collector.token").orElseThrow().equals("neutral-secret-fixture"), "no-GUI credential round trip");
        check(!Files.readString(RuntimeFiles.peekPluginCredentialPath("independent-collector")).contains("neutral-secret-fixture"), "encrypted on disk");
        var other = contexts.contextFor("independent-display").orElseThrow().getBean(PluginCredentials.class);
        try { other.read("independent-collector.token"); throw new AssertionError("cross-owner credentials accepted"); }
        catch(PluginCredentialException expected) { check(expected.getMessage().equals("PLUGIN_CREDENTIAL_UNDECLARED"), "cross-owner rejected"); }
        stopEventually(coordinator, "independent-collector");
        coordinator.start("independent-collector");
        try { credentials.delete("independent-collector.token"); throw new AssertionError("stale credential owner accepted"); }
        catch(PluginCredentialException expected) { check(expected.getMessage().equals("PLUGIN_CREDENTIAL_CLOSED"), "stale credentials rejected"); }
        var current = contexts.contextFor("independent-collector").orElseThrow().getBean(PluginCredentials.class);
        check(current.read("independent-collector.token").isPresent(), "replacement reads preserved credential");
        current.delete("independent-collector.token");
        check(current.read("independent-collector.token").isEmpty(), "credential deleted");
        check(binding.state() == ServiceBinding.State.CLOSED && !collector.isActive(), "stopped collector binding closed");

        var started = invoke(client, origin, cookie, "display", "start", "cancel");
        String job = started.path("id").asText();
        var jobContext = contexts.contextFor("independent-jobs").orElseThrow();
        check(((CountDownLatch)control(contexts, "jobs").getField("entered").get(null)).await(5, TimeUnit.SECONDS), "job actually running");
        check(invoke(client, origin, cookie, "display", "status", job).path("status").asText().equals("RUNNING"), "query independent job");
        invoke(client, origin, cookie, "display", "cancel", job);
        await(() -> {
            try { return invoke(client, origin, cookie, "display", "status", job).path("status").asText().equals("CANCELLED"); }
            catch(Exception error) { throw new AssertionError(error); }
        }, "job cancellation exits");
        control(contexts, "jobs").getField("entered").set(null, new CountDownLatch(1));
        invoke(client, origin, cookie, "display", "start", "hold");
        check(((CountDownLatch)control(contexts, "jobs").getField("entered").get(null)).await(5, TimeUnit.SECONDS), "held job entered");
        try { coordinator.stop("independent-jobs"); } catch(PluginLifecycleException pending) { }
        check(jobContext.isActive(), "child retained until actual job exit");
        check(post(client, origin, "/api/independent/display", "{\"operation\":\"start\",\"value\":\"late\"}", cookie).statusCode() == 503, "quiesced jobs reject new commands");
        ((CountDownLatch)control(contexts, "jobs").getField("release").get(null)).countDown();
        stopEventually(coordinator, "independent-jobs");
        check(!jobContext.isActive(), "job child closes after real drain");
        System.out.println("PASS frozen unknown collector/storage/display, third validator, two sinks, no-GUI credentials, independent jobs");
    }

    private static Class<?> control(ExternalPluginContextManager contexts, String suffix) throws Exception {
        return contexts.contextFor("independent-" + suffix).orElseThrow().getClassLoader().loadClass("acceptance.businessplugin.BusinessParticipant");
    }
    private static int counter(ExternalPluginContextManager contexts, String suffix, String name) throws Exception {
        return ((java.util.concurrent.atomic.AtomicInteger)control(contexts, suffix).getField(name).get(null)).get();
    }
    private static JsonNode invoke(HttpClient client, String origin, String cookie, String role, String operation, String value) throws Exception {
        var response = post(client, origin, "/api/independent/" + role, JSON.writeValueAsString(Map.of("operation", operation, "value", value)), cookie);
        check(response.statusCode() == 200, "business HTTP " + role + " " + operation + ": " + response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }
    private static HttpResponse<String> login(HttpClient client, String origin, Map<String, Object> extra) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("username", "acceptance"); body.put("password", "Isolated-acceptance-password-2026");
        body.putAll(extra);
        return post(client, origin, "/api/auth/login", JSON.writeValueAsString(body), null);
    }
    private static void denied(HttpResponse<String> response, int status) {
        check(response.statusCode() == status && response.headers().allValues("set-cookie").isEmpty(),
            "login refusal has no session cookie: " + response.statusCode());
        check(!response.body().contains("private-verification-details"), "verification internals sanitized");
    }
    static void verifyLogin(ConfigurableApplicationContext context, Path fixtures, String origin) throws Exception {
        var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
        var contexts = context.getBean(ExternalPluginContextManager.class);
        var client = HttpClient.newHttpClient();
        var mode = contexts.contextFor("neutral-login").orElseThrow().getClassLoader()
            .loadClass("acceptance.login.LoginParticipant").getField("mode");
        mode.set(null, "reject"); denied(login(client, origin, Map.of()), 403);
        mode.set(null, "fail"); denied(login(client, origin, Map.of()), 503);
        mode.set(null, "challenge");
        var browser = new ProcessBuilder("node", System.getProperty("acceptance.loginBrowser"));
        browser.environment().put("PAGE_ORIGIN", origin);
        var browserProcess = browser.inheritIO().start();
        if (!browserProcess.waitFor(90, TimeUnit.SECONDS)) browserProcess.destroyForcibly();
        check(!browserProcess.isAlive() && browserProcess.exitValue() == 0, "neutral challenge browser");
        var challenge = login(client, origin, Map.of());
        denied(challenge, 409);
        var proof = Map.<String, Object>of("verificationToken", JSON.readTree(challenge.body()).path("verificationToken").asText(),
            "verificationProofs", Map.of("neutral-policy", "neutral-proof"));
        var approved = login(client, origin, proof);
        check(approved.statusCode() == 200 && !approved.headers().allValues("set-cookie").isEmpty(), "verified login creates session");
        denied(login(client, origin, proof), 409);
        challenge = login(client, origin, Map.of());
        var stale = Map.<String, Object>of("verificationToken", JSON.readTree(challenge.body()).path("verificationToken").asText(),
            "verificationProofs", Map.of("neutral-policy", "neutral-proof"));
        stopEventually(coordinator, "neutral-login");
        denied(login(client, origin, Map.of()), 503);
        coordinator.start("neutral-login");
        denied(login(client, origin, stale), 409);
        activate(coordinator, fixtures.resolve("neutral-login-duplicate.jar"));
        denied(login(client, origin, Map.of()), 503);
        stopEventually(coordinator, "neutral-login-duplicate");
        coordinator.unload("neutral-login-duplicate");
        contexts.contextFor("neutral-login").orElseThrow().getClassLoader()
            .loadClass("acceptance.login.LoginParticipant").getField("mode").set(null, "approve");
        check(login(client, origin, Map.of()).statusCode() == 200, "login recovers after ambiguity withdrawn");
        System.out.println("PASS actual login allow/reject/challenge/proof/replay/failure/absence/ambiguity/stale without unauthorized cookies");
    }
}
