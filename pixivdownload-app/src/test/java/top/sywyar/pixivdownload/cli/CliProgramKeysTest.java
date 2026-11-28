package top.sywyar.pixivdownload.cli;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliProgramKeysTest {
    private static final String SESSION = "pixiv_session=session-token";
    private static final String PASSWORD = "admin-password-123";
    private static final String SECRET = "issued-secret-value";
    private static final String KEY_ID = "4f1c2b8e-1d2a-4c55-9a77-3e2f6f2a9b10";

    private final JsonMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> origins = new ConcurrentHashMap<>();
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private HttpServer server;
    private Locale previousLocale;

    @TempDir
    Path temp;

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startServer() throws IOException {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(route);
            bodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            origins.put(route, String.valueOf(exchange.getRequestHeaders().getFirst("Origin")));
            Handler handler = routes.get(route);
            if (handler == null) respond(exchange, 404, "{\"code\":\"not-found\",\"error\":\"Not found\"}");
            else handler.handle(exchange);
            exchange.close();
        });
        server.start();
        routes.put("POST /api/auth/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", SESSION + "; Path=/; HttpOnly; SameSite=Strict");
            respond(exchange, 200, "{\"success\":true}");
        });
        routes.put("POST /api/auth/logout", exchange -> respond(exchange, 200, "{\"success\":true}"));
        routes.put("GET /api/security/program-keys/permissions", authenticated(exchange -> respond(exchange, 200, """
                [{"id":"core:api-reference.read","endpoints":[{"method":"GET","path":"/api/openapi.json"}]},
                 {"id":"core:downloads.read","endpoints":[{"method":"GET","path":"/api/download/tasks"}]}]
                """)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        Locale.setDefault(previousLocale);
    }

    @Test
    @DisplayName("创建密钥时以管理员会话和同源来源写入，打印一次性密钥与验证命令并注销")
    void createsKeyThroughRunningInstance() throws Exception {
        routes.put("POST /api/security/program-keys", authenticated(exchange -> respond(exchange, 201,
                "{\"key\":{\"id\":\"" + KEY_ID + "\",\"policy\":{\"name\":\"agent\"}},\"secret\":\"" + SECRET + "\"}")));

        int exit = run(List.of(CliProgramKeys.CMD_CREATE, "--key-name=agent",
                "--key-permissions=core:api-reference.read,core:downloads.read", "--key-sources=127.0.0.1, 10.0.0.0/8"));

        assertThat(exit).isZero();
        JsonNode login = json.readTree(bodies.get("POST /api/auth/login"));
        assertThat(login.path("username").stringValue()).isEqualTo("admin");
        assertThat(login.path("password").stringValue()).isEqualTo(PASSWORD);
        JsonNode policy = json.readTree(bodies.get("POST /api/security/program-keys"));
        assertThat(policy.path("name").stringValue()).isEqualTo("agent");
        assertThat(policy.path("anyIp").booleanValue()).isFalse();
        assertThat(strings(policy.path("permissions")))
                .containsExactly("core:api-reference.read", "core:downloads.read");
        assertThat(strings(policy.path("networks")))
                .containsExactly("127.0.0.1", "10.0.0.0/8");
        assertThat(origins.get("POST /api/security/program-keys")).isEqualTo(base().toString());
        assertThat(calls).endsWith("POST /api/auth/logout");
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains(SECRET, KEY_ID, base() + CliProgramKeys.SELF_PATH, base() + "/openapi");
        assertThat(printed).doesNotContain(CliProgramKeys.REFERENCE_PERMISSION);
    }

    @Test
    @DisplayName("显式选择 any 时创建不限来源的密钥并提示缺少接口目录权限")
    void createsUnrestrictedKeyWithoutReferencePermission() throws Exception {
        routes.put("POST /api/security/program-keys", authenticated(exchange -> respond(exchange, 201,
                "{\"key\":{\"id\":\"" + KEY_ID + "\",\"policy\":{\"name\":\"monitor\"}},\"secret\":\"" + SECRET + "\"}")));
        Queue<String> answers = new ArrayDeque<>(List.of("monitor", "2", "any"));

        int exit = run(List.of(CliProgramKeys.CMD_CREATE), answers);

        assertThat(exit).isZero();
        JsonNode policy = json.readTree(bodies.get("POST /api/security/program-keys"));
        assertThat(policy.path("anyIp").booleanValue()).isTrue();
        assertThat(policy.path("networks").size()).isZero();
        assertThat(strings(policy.path("permissions")))
                .containsExactly("core:downloads.read");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(CliProgramKeys.REFERENCE_PERMISSION);
    }

    @Test
    @DisplayName("当前实例没有的权限在写入前拒绝，并仍注销会话")
    void rejectsUnknownPermissionBeforeWrite() {
        int exit = run(List.of(CliProgramKeys.CMD_CREATE, "--key-name=agent",
                "--key-permissions=core:missing", "--key-sources=any"));

        assertThat(exit).isEqualTo(2);
        assertThat(calls).doesNotContain("POST /api/security/program-keys").endsWith("POST /api/auth/logout");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("core:missing");
    }

    @Test
    @DisplayName("登录失败时保留服务端错误码与说明，不继续调用密钥接口")
    void keepsLoginFailureCode() {
        routes.put("POST /api/auth/login", exchange -> respond(exchange, 401,
                "{\"code\":\"setup.login.invalid-credentials\",\"error\":\"Invalid username\\u001b[31m or password\"}"));
        var logs = capture();
        try {
            int exit = run(List.of(CliProgramKeys.CMD_LIST));

            assertThat(exit).isEqualTo(1);
            String printed = err.toString(StandardCharsets.UTF_8);
            assertThat(printed).contains("401", "setup.login.invalid-credentials", "Invalid username").doesNotContain("\u001b");
            assertThat(calls).containsExactly("POST /api/auth/login");
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("action=program-keys", "stage=login", "reason=HTTP", "code=setup.login.invalid-credentials"));
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD, "session-token"));
        } finally {
            release(logs);
        }
    }

    @Test
    @DisplayName("撤销前确认并携带当前修订号；拒绝确认时不写入")
    void revokesOnlyAfterConfirmation() throws Exception {
        routes.put("GET /api/security/program-keys", authenticated(exchange -> respond(exchange, 200,
                "[{\"id\":\"" + KEY_ID + "\",\"revision\":7,\"policy\":{\"name\":\"agent\",\"permissions\":[],\"anyIp\":true,\"networks\":[]},\"createdAt\":0}]")));
        routes.put("DELETE /api/security/program-keys/" + KEY_ID, authenticated(exchange -> {
            exchange.sendResponseHeaders(204, -1);
        }));

        int declined = run(List.of(CliProgramKeys.CMD_REVOKE, "--key-id=" + KEY_ID), new ArrayDeque<>(List.of("n")));
        assertThat(declined).isEqualTo(2);
        assertThat(calls).doesNotContain("DELETE /api/security/program-keys/" + KEY_ID);

        int confirmed = run(List.of(CliProgramKeys.CMD_REVOKE, "--key-id=" + KEY_ID), new ArrayDeque<>(List.of("y")));
        assertThat(confirmed).isZero();
        assertThat(json.readTree(bodies.get("DELETE /api/security/program-keys/" + KEY_ID)).path("revision").asLong()).isEqualTo(7);
        assertThat(origins.get("DELETE /api/security/program-keys/" + KEY_ID)).isEqualTo(base().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("写入后响应无法读取时提示结果不确定，读取失败时不提示")
    void reportsUncertainOutcomeOnlyAfterWrite(boolean chunked) {
        routes.put("POST /api/security/program-keys", authenticated(exchange -> respond(exchange, 201, "{\"key\":{}}")));
        int afterWrite = run(List.of(CliProgramKeys.CMD_CREATE, "--key-name=a", "--key-permissions=", "--key-sources=any"));
        assertThat(afterWrite).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(CliProgramKeys.CMD_LIST);

        err.reset();
        routes.put("GET /api/security/program-keys/permissions", authenticated(exchange -> {
            byte[] large = new byte[CliProgramKeyClient.RESPONSE_LIMIT + 1];
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, chunked ? 0 : large.length);
            exchange.getResponseBody().write(large);
        }));
        int beforeWrite = run(List.of(CliProgramKeys.CMD_CREATE, "--key-name=a", "--key-permissions=", "--key-sources=any"));
        assertThat(beforeWrite).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain(CliProgramKeys.CMD_LIST);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(4)
    @DisplayName("创建密钥收到响应头后，取消停滞或已声明超限的响应体")
    void cancelsStalledOrOversizedWriteResponse(boolean oversized) throws Exception {
        var finishBody = new CountDownLatch(1);
        routes.put("POST /api/security/program-keys", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, oversized ? CliProgramKeyClient.RESPONSE_LIMIT + 1L : 2);
                var body = exchange.getResponseBody();
                body.write('{');
                body.flush();
                finishBody.await(5, TimeUnit.SECONDS);
                body.write('}');
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        try (var client = new CliProgramKeyClient(base(), Locale.ENGLISH, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.send("POST", "/api/security/program-keys", Map.of("name", "agent")))
                    .isInstanceOfSatisfying(CliProgramKeyClient.Failure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(
                                oversized ? CliProgramKeyClient.Kind.RESPONSE : CliProgramKeyClient.Kind.TIMEOUT);
                        assertThat(failure.status()).isEqualTo(201);
                        assertThat(failure.afterWrite().outcomeUnknown()).isTrue();
                    });
            assertThat(calls).containsExactly("POST /api/security/program-keys");
        } finally {
            finishBody.countDown();
        }
    }

    @Test
    @DisplayName("密钥命令与本地管理命令同时出现时不发起任何请求")
    void rejectsConflictingCommands() {
        int exit = run(List.of(CliProgramKeys.CMD_LIST, CliSetupCommand.CMD_SETUP));

        assertThat(exit).isEqualTo(2);
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("实例地址只接受带主机的 http/https，并拒绝凭据、查询和片段")
    void validatesServerAddress() {
        assertThat(CliProgramKeyClient.parseBase("https://example.test:8443/base/")).isEqualTo(URI.create("https://example.test:8443/base"));
        for (String invalid : List.of("ftp://example.test", "http://user@example.test", "http://example.test/?a=1",
                "http://example.test/#x", "example.test:6999", "")) {
            assertThatThrownBy(() -> CliProgramKeyClient.parseBase(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("未指定地址时按配置与启动参数推导本机实例地址，且不创建配置文件")
    void derivesServerFromConfiguration() throws Exception {
        String previous = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        Path config = temp.resolve("config");
        try {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, config.toString());
            assertThat(CliProgramKeys.configuredServer(new String[0])).isEqualTo(URI.create("http://localhost:6999"));
            assertThat(config).doesNotExist();
            Files.createDirectories(config);
            Files.writeString(config.resolve("config.yaml"),
                    "server.port: 7123\nserver.ssl.enabled: true\nssl.domain: example.test\n", StandardCharsets.UTF_8);
            assertThat(CliProgramKeys.configuredServer(new String[0])).isEqualTo(URI.create("https://example.test:7123"));
            assertThat(CliProgramKeys.configuredServer(new String[]{"--server.port=7200"}))
                    .isEqualTo(URI.create("https://example.test:7200"));
        } finally {
            if (previous == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previous);
        }
    }

    private int run(List<String> args) {
        return run(args, new ArrayDeque<>());
    }

    private int run(List<String> args, Queue<String> answers) {
        var all = new java.util.ArrayList<>(args);
        all.add("--server-url=" + base());
        all.add("--username=admin");
        all.add("--password=" + PASSWORD);
        CliProgramKeys.Prompts prompts = new CliProgramKeys.Prompts() {
            @Override public String line(String prompt) { return answers.poll(); }
            @Override public String password(String prompt) { return answers.poll(); }
        };
        return CliProgramKeys.run(all.toArray(String[]::new), () -> {
            throw new AssertionError("explicit server expected");
        }, prompts, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static List<String> strings(JsonNode array) {
        var values = new java.util.ArrayList<String>();
        for (JsonNode value : array) values.add(value.stringValue());
        return values;
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Handler authenticated(Handler handler) {
        return exchange -> {
            if (!SESSION.equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                respond(exchange, 403, "{\"code\":\"auth.program.management\",\"error\":\"Sign in\"}");
                return;
            }
            handler.handle(exchange);
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ListAppender<ILoggingEvent> capture() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(CliProgramKeys.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(CliProgramKeys.class)).detachAppender(appender);
    }
}
