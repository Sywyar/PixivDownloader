package acceptance;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import static acceptance.PageEditorAcceptance.*;

/** 同一独立插件的本地计算、图片交互与官方在线测试服务，基础产物由既有入口冻结。 */
final class CaptchaAcceptance {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    static void verify(ConfigurableApplicationContext context, String origin, String cookie, Path fixed) throws Exception {
        var sensitive = new java.util.ArrayList<>(List.of("Isolated-page-password-2026",
                "XXXX.DUMMY.TOKEN.XXXX", "1x0000000000000000000000000000000AA"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            new PropertiesConfigFileEditor(RuntimeFiles.resolvePluginConfigPath("captcha", "properties")).writeAll(Map.of(
                    "captcha.turnstile-sitekey", "1x00000000000000000000AA",
                    "captcha.turnstile-hostname", "example.com",
                    "captcha.turnstile-test-mode", "true",
                    "captcha.recaptcha-sitekey", "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI",
                    "captcha.recaptcha-test-mode", "true"));
            var coordinator = context.getBean(ExternalPluginLifecycleCoordinator.class);
            for (String owner : List.of("operation-form", "captcha")) {
                Path artifact = fixed.resolve("fixture/" + owner + ".jar");
                var pending = coordinator.installOrUpdate(artifact, false, origin(artifact, false, null));
                check(pending.installResult().trustRequirement() != null,
                        "plugin trust request " + owner + ": " + pending.installResult().outcome() + " " + pending.installResult().messages());
                var active = coordinator.installOrUpdate(artifact, false,
                        origin(artifact, false, pending.installResult().trustRequirement().artifactSha256()));
                check(active.activated(), "independent CAPTCHA/form plugin activated: " + owner);
            }
            String privateKey = verifyIndependentCredentials(context, coordinator);
            sensitive.add(privateKey);
            String envelope = java.nio.file.Files.readString(RuntimeFiles.resolvePluginCredentialPath("captcha"));
            check(!envelope.contains(privateKey) && envelope.contains("pixivdownload-plugin-credentials-v1"),
                    "automatically initialized key is persisted only in the encrypted owner store");
            context.getBean(top.sywyar.pixivdownload.config.RuntimeConfigReloadService.class).reloadHotConfig(
                    List.of("captcha.turnstile-sitekey", "captcha.turnstile-hostname", "captcha.turnstile-test-mode"));
            var child = context.getBean(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager.class)
                    .contextFor("captcha").orElseThrow();
            check("true".equals(child.getEnvironment().getProperty("captcha.turnstile-test-mode")),
                    "saved configuration is loaded through the production hot-reload path");
            String script = Path.of("pixivdownload-app/src/test/resources/page-components/captcha-browser.mjs").toAbsolutePath().toString();
            var proxy = context.getBean(ProxyConfig.class);
            boolean enabled = proxy.isEnabled(); String host = proxy.getHost(); int port = proxy.getPort();
            var denied = new AtomicInteger();
            try (var server = new ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())) {
                var thread = new Thread(() -> {
                    while (!server.isClosed()) {
                        try (var socket = server.accept()) {
                            denied.incrementAndGet();
                            socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        } catch (java.io.IOException closed) { if (!server.isClosed()) throw new IllegalStateException("EGRESS_PROXY_FAILED"); }
                    }
                }, "captcha-egress-denial");
                thread.setDaemon(true); thread.start();
                proxy.setHost("127.0.0.1"); proxy.setPort(server.getLocalPort()); proxy.setEnabled(true);
                try (var probe = context.getBean(OutboundHttpClientFactory.class).open(OutboundHttpClientProfile.credentialed(
                        Duration.ofSeconds(2), Duration.ofSeconds(2), OutboundHttpRoute.inherit()))) {
                    try (var rejected = probe.exchangeStream(new OutboundHttpRequest(URI.create("https://captcha-egress.invalid"), "GET", Map.of(), null))) {
                        check(rejected.statusCode() == 403 && denied.get() > 0, "real managed HTTP receives the rejecting proxy response");
                    }
                    catch (OutboundHttpTransportException expected) { check(denied.get() > 0, "real managed HTTP reached the rejecting proxy"); }
                }
                int baseline = denied.get();
                browser(script, origin, cookie, "altcha", fixed);
                browser(script, origin, cookie, "altcha-pbkdf2", fixed);
                verifyRotation(origin, "captcha-altcha-pbkdf2", "captcha.altcha-secret", privateKey, sensitive);
                verifyImages(context, script, origin, cookie, fixed);
                check(denied.get() == baseline, "offline schemes perform no third-party server request");
                new PluginCredentialStore().update("captcha", Map.of("captcha.altcha-secret", "",
                        "captcha.turnstile-secret", "1x0000000000000000000000000000000AA"));
                coordinator.stop("captcha"); coordinator.start("captcha");
                proxy.setHost(host); proxy.setPort(port); proxy.setEnabled(enabled);
                waitForLoginWindow();
                new PluginCredentialStore().update("captcha", Map.of("captcha.recaptcha-secret", "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe"));
                browser(script, origin, cookie, "recaptcha", fixed);
                verifyRotation(origin, "captcha-recaptcha", "captcha.recaptcha-secret",
                        "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe", sensitive);
                waitForLoginWindow();
                browser(script, origin, cookie, "turnstile", fixed);
                verifyRotation(origin, "captcha-turnstile", "captcha.turnstile-secret",
                        "1x0000000000000000000000000000000AA", sensitive);
                waitForLoginWindow();
                for (String secret : List.of("2x0000000000000000000000000000000AA", "3x0000000000000000000000000000000AA")) {
                    new PluginCredentialStore().update("captcha", Map.of("captcha.turnstile-secret", secret));
                    var login = JSON.createObjectNode().put("username", "acceptance").put("password", "Isolated-page-password-2026");
                    var challenge = request(origin, "/api/auth/login", login.toString(), null);
                    check(challenge.statusCode() == 409, "official rejection configuration issues a challenge");
                    login.put("operationToken", JSON.readTree(challenge.body()).path("token").asText());
                    sensitive.add(login.path("operationToken").asText());
                    sensitive.add(secret);
                    login.putObject("operationProofs").put("captcha-turnstile", "XXXX.DUMMY.TOKEN.XXXX");
                    var rejected = request(origin, "/api/auth/login", login.toString(), null);
                    check(rejected.statusCode() == 403 && rejected.headers().firstValue("Set-Cookie").isEmpty(),
                            "official failed/spent response cannot create a session");
                    check(request(origin, "/api/auth/login", login.toString(), null).statusCode() == 409,
                            "failed official proof is consumed and cannot be retried");
                }
                new PluginCredentialStore().update("captcha", Map.of("captcha.turnstile-secret", ""));
                var login = JSON.createObjectNode().put("username", "acceptance").put("password", "Isolated-page-password-2026");
                var missing = request(origin, "/api/auth/login", login.toString(), null);
                check(missing.statusCode() == 503 && missing.headers().firstValue("Set-Cookie").isEmpty(),
                        "missing credentials cannot create a session");
                new PluginCredentialStore().update("captcha", Map.of("captcha.turnstile-secret", "1x0000000000000000000000000000000AA"));
                var challenge = request(origin, "/api/auth/login", login.toString(), null);
                check(challenge.statusCode() == 409, "direct login still requires the published CAPTCHA");
                login.put("operationToken", JSON.readTree(challenge.body()).path("token").asText());
                sensitive.add(login.path("operationToken").asText());
                login.putObject("operationProofs").put("captcha-turnstile", "XXXX.DUMMY.TOKEN.XXXX");
                proxy.setHost("127.0.0.1"); proxy.setPort(server.getLocalPort()); proxy.setEnabled(true);
                int beforeFailure = denied.get();
                var unavailable = request(origin, "/api/auth/login", login.toString(), null);
                check(unavailable.statusCode() == 503 && unavailable.headers().firstValue("Set-Cookie").isEmpty()
                        && denied.get() > beforeFailure, "real Siteverify transport failure cannot create a session");
                proxy.setHost(host); proxy.setPort(port); proxy.setEnabled(enabled);
                for (String path : List.of("/api/web/page-publications/core:login", "/api/page-editor/catalog",
                        "/api/page-editor/documents/core:login")) {
                    var response = request(origin, path, null, cookie);
                    check(response.statusCode() == 200, "publication evidence is readable");
                    for (String secret : sensitive) check(!response.body().contains(secret), "public/editor values omit sensitive data");
                }
                try (var files = java.nio.file.Files.walk(fixed.resolve("runtime/data/web-publications"))) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                        String content = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
                        for (String secret : sensitive) check(!content.contains(secret), "formal snapshots omit sensitive data");
                    }
                }
                coordinator.stop("captcha");
                check(request(origin, "/api/auth/login", "{\"username\":\"acceptance\",\"password\":\"Isolated-page-password-2026\"}", null).statusCode() == 503,
                        "missing CAPTCHA cannot bypass login");
            } finally { proxy.setHost(host); proxy.setPort(port); proxy.setEnabled(enabled); }
            waitForLoginWindow();
            coordinator.start("captcha");
            var publications = context.getBean(top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.class);
            var committed = publications.snapshot();
            var database = context.getBean(javax.sql.DataSource.class);
            try (var connection = database.getConnection()) { check(!connection.isClosed(), "main database is initially usable"); }
            ((AutoCloseable) database).close();
            boolean databaseFailed = false;
            try (var ignored = database.getConnection()) { }
            catch (java.sql.SQLException expected) { databaseFailed = true; }
            check(databaseFailed, "actual main database pool rejects connections");
            var protectedLogin = request(origin, "/api/auth/login",
                    "{\"username\":\"acceptance\",\"password\":\"Isolated-page-password-2026\"}", null);
            check(protectedLogin.statusCode() == 409 && protectedLogin.headers().firstValue("Set-Cookie").isEmpty(),
                    "main database failure cannot erase mandatory verification");
            check(publications.snapshot().equals(committed), "main database failure preserves the complete formal revision");
            check(request(origin, "/api/web/page-publications/core:login", null, null).statusCode() == 200,
                    "formal page remains readable without main database connections");
            System.out.println("CAPTCHA_MAIN_DATABASE_FAILURE_RETAINS_PROTECTION_PASSED");
            for (var event : capture.list) {
                String diagnostic = event.getFormattedMessage()
                        + ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : sensitive)
                    check(!diagnostic.contains(secret), "runtime diagnostics and exceptions omit sensitive data");
            }
            System.out.println("CAPTCHA_PUBLICATION_AND_RUNTIME_REDACTION_PASSED");
            System.out.println("CAPTCHA_OFFLINE_EGRESS_BLOCKED_AND_TURNSTILE_OFFICIAL_TEST_PASSED");
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

    private static String verifyIndependentCredentials(ConfigurableApplicationContext context,
            ExternalPluginLifecycleCoordinator coordinator) throws java.io.IOException {
        var credentials = new PluginCredentialStore();
        String privateKey = credentials.readAll("captcha").get("captcha.altcha-secret");
        check(privateKey != null && java.util.HexFormat.of().parseHex(privateKey).length == 32,
                "first activation generates a local signing key without manual configuration");
        for (String mode : List.of("neither", "turnstile", "altcha")) {
            coordinator.stop("captcha");
            credentials.update("captcha", Map.of("captcha.turnstile-secret",
                    mode.equals("turnstile") ? "1x0000000000000000000000000000000AA" : ""));
            coordinator.start("captcha");
            check(privateKey.equals(credentials.readAll("captcha").get("captcha.altcha-secret")),
                    "reactivation preserves the encrypted local signing key");
            var child = context.getBean(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager.class)
                    .contextFor("captcha").orElseThrow();
            var providers = child.getBeansOfType(top.sywyar.pixivdownload.plugin.api.security.OperationVerification.class);
            check(child.isActive() && providers.size() == 6, "all CAPTCHA capabilities activate with independently configured credentials");
            for (var provider : providers.values()) {
                check(!provider.generation().isBlank() && provider.generation().equals(provider.generation()), "stable capability generation");
                boolean configured = provider.policyId().startsWith("captcha-altcha")
                        || provider.policyId().equals("captcha-text") || provider.policyId().equals("captcha-slider")
                        || provider.policyId().equals("captcha-turnstile") && mode.equals("turnstile");
                if (!configured) {
                    boolean rejected = false;
                    try { provider.verify(new top.sywyar.pixivdownload.plugin.api.security.OperationVerificationRequest(
                            "unknown:submit", "subject", "{}", "attempt-test", "revision", "", false, "")); }
                    catch (IllegalStateException failure) { rejected = "CAPTCHA_CREDENTIAL_MISSING".equals(failure.getMessage()); }
                    check(rejected, "unconfigured capability remains fail-closed");
                }
            }
        }
        System.out.println("CAPTCHA_INDEPENDENT_CREDENTIAL_LIFECYCLE_PASSED");
        return privateKey;
    }

    private static void verifyImages(ConfigurableApplicationContext context, String script, String origin, String cookie, Path fixed) throws Exception {
        var child = context.getBean(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager.class)
                .contextFor("captcha").orElseThrow();
        Object store = child.getBean("imageChallenges");
        var entries = store.getClass().getDeclaredField("entries"); entries.setAccessible(true);
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        Path oracle = fixed.resolve("captcha-test-answers.json");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        // 测试进程读取真实内存答案，不改挑战、签名或生产 HTTP；这里只验收提交流程，不测识图难度。
        var writer = executor.submit(() -> {
            while (!stopped.get()) {
                var answers = new java.util.HashMap<String, String>();
                synchronized (store) {
                    for (var entry : ((Map<?, ?>) entries.get(store)).entrySet()) {
                        var accessor = entry.getValue().getClass().getDeclaredMethod("images"); accessor.setAccessible(true);
                        Object images = accessor.invoke(entry.getValue());
                        var answer = images.getClass().getDeclaredMethod("answer"); answer.setAccessible(true);
                        answers.put(entry.getKey().toString(), answer.invoke(images).toString());
                    }
                }
                java.nio.file.Files.writeString(oracle, JSON.writeValueAsString(answers), StandardCharsets.UTF_8);
                Thread.sleep(50);
            }
            return null;
        });
        try {
            for (String method : List.of("text", "slider")) {
                waitForLoginWindow();
                browser(script, origin, cookie, method, fixed);
            }
        } finally {
            stopped.set(true);
            try { writer.get(10, java.util.concurrent.TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); java.nio.file.Files.deleteIfExists(oracle); }
        }
    }

    private static void verifyRotation(String origin, String policy, String key, String original, List<String> sensitive) throws Exception {
        waitForLoginWindow();
        var login = JSON.createObjectNode().put("username", "acceptance").put("password", "Isolated-page-password-2026");
        var challenge = request(origin, "/api/auth/login", login.toString(), null);
        check(challenge.statusCode() == 409, "configured scheme issues a challenge without credentials for other schemes");
        login.put("operationToken", JSON.readTree(challenge.body()).path("token").asText());
        sensitive.add(login.path("operationToken").asText());
        login.putObject("operationProofs").put(policy, "stale-proof");
        var credentials = new PluginCredentialStore();
        try {
            credentials.update("captcha", Map.of(key, original + "-rotated"));
            var stale = request(origin, "/api/auth/login", login.toString(), null);
            check(stale.statusCode() == 409 && stale.headers().firstValue("Set-Cookie").isEmpty(), "credential rotation rejects the old challenge");
            credentials.update("captcha", Map.of(key, ""));
            login.remove(List.of("operationToken", "operationProofs"));
            var missing = request(origin, "/api/auth/login", login.toString(), null);
            check(missing.statusCode() == 503 && missing.headers().firstValue("Set-Cookie").isEmpty(), "required unconfigured scheme cannot create a session");
        } finally { credentials.update("captcha", Map.of(key, original)); }
        System.out.println("CAPTCHA_CREDENTIAL_ROTATION_AND_ABSENCE_PASSED " + policy);
    }

    private static void waitForLoginWindow() throws InterruptedException {
        // 保留生产限流，不让上一组已完成的登录请求耗尽本组窗口。
        System.out.println("CAPTCHA_WAITING_FOR_LOGIN_RATE_WINDOW");
        Thread.sleep(60_000 - System.currentTimeMillis() % 60_000);
    }
}
