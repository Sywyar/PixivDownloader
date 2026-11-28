package top.sywyar.pixivdownload.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import tools.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.http.ServerAddressProvider;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 连接正在运行的实例管理程序访问密钥。
 *
 * <p>权限目录只有加载了插件的实例才能给出，因此本命令以管理员身份登录后调用现有密钥接口，
 * 不占用单实例锁，也不读写本地状态文件。命令执行后进程直接退出。</p>
 */
public final class CliProgramKeys {
    public static final String CMD_LIST = "--program-keys";
    public static final String CMD_PERMISSIONS = "--program-key-permissions";
    public static final String CMD_CREATE = "--program-key-create";
    public static final String CMD_REVOKE = "--program-key-revoke";
    public static final String FLAG_SERVER = "--server-url";
    public static final String FLAG_NAME = "--key-name";
    public static final String FLAG_PERMISSIONS = "--key-permissions";
    public static final String FLAG_SOURCES = "--key-sources";
    public static final String FLAG_ID = "--key-id";

    static final Set<String> VALUE_FLAGS = Set.of(FLAG_SERVER, FLAG_NAME, FLAG_PERMISSIONS, FLAG_SOURCES, FLAG_ID);
    static final String REFERENCE_PERMISSION = "core:api-reference.read";
    static final String SELF_PATH = "/api/security/program-keys/self";
    private static final String KEYS_PATH = "/api/security/program-keys";
    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_INPUT = 2;
    private static final Logger LOG = LoggerFactory.getLogger(CliProgramKeys.class);

    /** 交互输入；没有可读输入时返回 {@code null}。 */
    interface Prompts {
        String line(String prompt);
        String password(String prompt);
    }

    private CliProgramKeys() {
    }

    /** 命中密钥命令时执行并以退出码结束进程；否则直接返回。 */
    public static void handleIfPresent(String[] args) {
        if (!CliSetupArguments.containsProgramKeyCommand(args)) return;
        Prompts prompts = new Prompts() {
            @Override public String line(String prompt) { return CliSetupCommand.promptLine(prompt); }
            @Override public String password(String prompt) { return CliSetupCommand.promptPassword(prompt); }
        };
        System.exit(run(args, () -> configuredServer(args), prompts, System.out, System.err));
    }

    static int run(String[] args, Supplier<URI> defaultServer, Prompts prompts, PrintStream out, PrintStream err) {
        var commands = new LinkedHashSet<String>();
        for (String arg : args) {
            if (CliSetupArguments.PROGRAM_KEY_COMMANDS.contains(arg) || CliSetupArguments.isCommand(arg)) commands.add(arg);
        }
        String command = commands.iterator().next();
        String action = command.substring(2);
        if (commands.size() > 1) {
            var conflict = commands.stream().skip(1).findFirst().orElseThrow();
            err.println(message("cli.error.conflicting-commands", command, conflict));
            return rejected(action, "conflicting-commands");
        }
        Map<String, String> flags = values(args);
        URI server;
        try {
            server = flags.containsKey(FLAG_SERVER) ? CliProgramKeyClient.parseBase(flags.get(FLAG_SERVER)) : defaultServer.get();
        } catch (IllegalArgumentException invalid) {
            err.println(message("cli.keys.error.server", FLAG_SERVER));
            return rejected(action, "server-url-invalid");
        }
        UUID revokeId = null;
        if (command.equals(CMD_REVOKE)) {
            String id = flags.containsKey(FLAG_ID) ? flags.get(FLAG_ID) : prompts.line(message("cli.keys.prompt.id"));
            try {
                revokeId = UUID.fromString(id == null ? "" : id.trim());
            } catch (IllegalArgumentException invalid) {
                err.println(message("cli.keys.error.id", FLAG_ID));
                return rejected(action, "key-id-invalid");
            }
        }
        if (server.getScheme().equals("http") && !loopback(server.getHost())) {
            err.println(message("cli.keys.warn.plain-http", server));
        }
        CliSetupArguments credentials = CliSetupArguments.parse(args);
        String username = credentials.username();
        if (username == null || username.isBlank()) username = prompts.line(message("cli.prompt.username"));
        if (username == null || username.isBlank()) {
            err.println(message("cli.error.username-required"));
            return rejected(action, "username-missing");
        }
        String password = credentials.password();
        if (password == null || password.isEmpty()) password = prompts.password(message("cli.prompt.password"));
        if (password == null || password.isEmpty()) {
            err.println(message("cli.error.password-required"));
            return rejected(action, "password-missing");
        }

        out.println(message("cli.keys.connecting", server));
        String stage = "login";
        try (var client = new CliProgramKeyClient(server, Locale.getDefault())) {
            client.login(username.trim(), password);
            stage = "request";
            return switch (command) {
                case CMD_LIST -> list(client, out);
                case CMD_PERMISSIONS -> permissions(client, out);
                case CMD_CREATE -> create(client, flags, prompts, out, err);
                default -> revoke(client, revokeId, prompts, out, err);
            };
        } catch (CliProgramKeyClient.Failure failure) {
            printFailure(failure, server, err);
            LOG.warn("Program key CLI command failed: action={}, stage={}, reason={}, status={}, code={}",
                    action, stage, failure.kind(), failure.status(), failure.code() == null ? "-" : failure.code());
            return EXIT_FAILED;
        }
    }

    private static int list(CliProgramKeyClient client, PrintStream out) throws CliProgramKeyClient.Failure {
        List<JsonNode> keys = items(client.get(KEYS_PATH));
        if (keys.isEmpty()) {
            out.println(message("cli.keys.list.empty", CMD_CREATE));
        } else {
            out.println(message("cli.keys.list.title", keys.size()));
            var dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault());
            for (JsonNode key : keys) {
                JsonNode policy = key.path("policy");
                out.println();
                out.println("  " + text(policy.path("name")));
                out.println("    " + message("cli.keys.list.id", text(key.path("id"))));
                out.println("    " + message("cli.keys.list.policy", policy.path("permissions").size(), sources(policy)));
                long used = key.path("lastUsedAt").asLong(0);
                out.println("    " + message("cli.keys.list.times",
                        dates.format(Instant.ofEpochMilli(key.path("createdAt").asLong(0))),
                        used > 0 ? dates.format(Instant.ofEpochMilli(used)) : message("cli.keys.list.unused")));
            }
        }
        LOG.info("Program key CLI command finished: action=program-keys, keys={}", keys.size());
        return EXIT_OK;
    }

    private static int permissions(CliProgramKeyClient client, PrintStream out) throws CliProgramKeyClient.Failure {
        List<JsonNode> catalog = items(client.get(KEYS_PATH + "/permissions"));
        out.println(message("cli.keys.permissions.title", catalog.size()));
        for (JsonNode permission : catalog) {
            out.println();
            out.println("  " + text(permission.path("id")));
            for (JsonNode endpoint : items(permission.path("endpoints"))) {
                out.println("    " + text(endpoint.path("method")) + " " + text(endpoint.path("path")));
            }
        }
        LOG.info("Program key CLI command finished: action=program-key-permissions, permissions={}", catalog.size());
        return EXIT_OK;
    }

    private static int create(CliProgramKeyClient client, Map<String, String> flags, Prompts prompts,
                              PrintStream out, PrintStream err) throws CliProgramKeyClient.Failure {
        List<String> catalog = items(client.get(KEYS_PATH + "/permissions")).stream()
                .map(permission -> text(permission.path("id"))).toList();
        String name = flags.containsKey(FLAG_NAME) ? flags.get(FLAG_NAME) : prompts.line(message("cli.keys.prompt.name"));
        if (name == null || name.isBlank()) {
            err.println(message("cli.keys.error.name", FLAG_NAME));
            return rejected("program-key-create", "name-missing");
        }
        String selection = flags.get(FLAG_PERMISSIONS);
        if (selection == null) {
            out.println(message("cli.keys.permissions.choose"));
            for (int index = 0; index < catalog.size(); index++) {
                out.println(String.format("  %3d  %s", index + 1, catalog.get(index)));
            }
            selection = prompts.line(message("cli.keys.prompt.permissions"));
            if (selection == null) {
                err.println(message("cli.keys.error.cancelled"));
                return rejected("program-key-create", "cancelled");
            }
        }
        var chosen = new LinkedHashSet<String>();
        var unknown = new ArrayList<String>();
        for (String token : tokens(selection)) {
            String id = token.chars().allMatch(Character::isDigit) && !flags.containsKey(FLAG_PERMISSIONS)
                    ? number(token, catalog) : token;
            if (id != null && catalog.contains(id)) chosen.add(id);
            else unknown.add(CliProgramKeyClient.displayText(token));
        }
        if (!unknown.isEmpty()) {
            err.println(message("cli.keys.error.permissions", String.join(", ", unknown.subList(0, Math.min(unknown.size(), 10))),
                    CMD_PERMISSIONS));
            return rejected("program-key-create", "permissions-unknown");
        }
        String sourceText = flags.containsKey(FLAG_SOURCES) ? flags.get(FLAG_SOURCES) : prompts.line(message("cli.keys.prompt.sources"));
        List<String> sources = tokens(sourceText);
        boolean any = sources.size() == 1 && sources.getFirst().equalsIgnoreCase("any");
        if (sources.isEmpty()) {
            err.println(message("cli.keys.error.sources", FLAG_SOURCES));
            return rejected("program-key-create", "sources-missing");
        }
        if (chosen.isEmpty()) out.println(message("cli.keys.permissions.none"));

        var policy = new LinkedHashMap<String, Object>();
        policy.put("name", name.trim());
        policy.put("permissions", List.copyOf(chosen));
        policy.put("anyIp", any);
        policy.put("networks", any ? List.of() : sources);
        JsonNode created;
        try {
            created = client.send("POST", KEYS_PATH, policy);
        } catch (CliProgramKeyClient.Failure failure) {
            throw failure.afterWrite();
        }
        String id = created == null ? "" : text(created.path("key").path("id"));
        String secret = created != null && created.path("secret").isString() ? created.path("secret").stringValue() : "";
        if (id.isEmpty() || secret.isEmpty())
            throw new CliProgramKeyClient.Failure(CliProgramKeyClient.Kind.RESPONSE, 201, null, null).afterWrite();

        URI self = client.resolve(SELF_PATH);
        out.println();
        out.println(message("cli.keys.created", text(created.path("key").path("policy").path("name")), id));
        out.println(message("cli.keys.created.secret"));
        out.println("  " + secret);
        out.println();
        out.println(message("cli.keys.created.verify"));
        out.println("  curl -H \"Authorization: Bearer $PIXIV_PROGRAM_KEY\" " + self);
        out.println("  curl.exe -H \"Authorization: Bearer $env:PIXIV_PROGRAM_KEY\" " + self + "   # PowerShell");
        out.println();
        out.println(message("cli.keys.created.reference", client.resolve("/openapi")));
        if (!chosen.contains(REFERENCE_PERMISSION)) out.println(message("cli.keys.created.no-reference", REFERENCE_PERMISSION));
        if (client.base().getScheme().equals("http") && !loopback(client.base().getHost())) {
            out.println(message("cli.keys.created.https"));
        }
        LOG.info("Program key CLI command finished: action=program-key-create, keyId={}, permissions={}, unrestrictedIp={}",
                id, chosen.size(), any);
        return EXIT_OK;
    }

    private static int revoke(CliProgramKeyClient client, UUID id, Prompts prompts,
                              PrintStream out, PrintStream err) throws CliProgramKeyClient.Failure {
        JsonNode target = items(client.get(KEYS_PATH)).stream()
                .filter(key -> id.toString().equalsIgnoreCase(text(key.path("id")))).findFirst().orElse(null);
        if (target == null) {
            err.println(message("cli.keys.error.not-found", id, CMD_LIST));
            return rejected("program-key-revoke", "key-not-found");
        }
        String name = text(target.path("policy").path("name"));
        String answer = prompts.line(message("cli.keys.prompt.revoke", name));
        if (answer == null || !answer.trim().toLowerCase(Locale.ROOT).matches("y|yes")) {
            out.println(message("cli.keys.revoke.cancelled"));
            return rejected("program-key-revoke", "cancelled");
        }
        try {
            client.send("DELETE", KEYS_PATH + "/" + id, Map.of("revision", target.path("revision").asLong(0)));
        } catch (CliProgramKeyClient.Failure failure) {
            throw failure.afterWrite();
        }
        out.println(message("cli.keys.revoked", name, id));
        LOG.info("Program key CLI command finished: action=program-key-revoke, keyId={}", id);
        return EXIT_OK;
    }

    private static void printFailure(CliProgramKeyClient.Failure failure, URI server, PrintStream err) {
        String line = switch (failure.kind()) {
            case CONNECT -> message("cli.keys.error.connect", server, FLAG_SERVER);
            case TRANSPORT -> message("cli.keys.error.transport", server);
            case TIMEOUT -> message("cli.keys.error.timeout", server);
            case TLS -> message("cli.keys.error.tls", server);
            case REDIRECT -> failure.detail() == null ? message("cli.keys.error.redirect", failure.status(), FLAG_SERVER)
                    : message("cli.keys.error.redirect-to", failure.detail(), FLAG_SERVER);
            case RESPONSE -> message("cli.keys.error.response", FLAG_SERVER);
            case HTTP -> failure.detail() == null ? message("cli.keys.error.status", failure.status())
                    : message("cli.keys.error.http", failure.status(), failure.detail())
                    + (failure.code() == null ? "" : " [" + failure.code() + "]");
        };
        err.println(line);
        if (failure.outcomeUnknown()) err.println(message("cli.keys.error.uncertain", CMD_LIST));
    }

    /** 未指定地址时，按与服务端相同的规则从当前配置推导本机实例地址；只读，不创建或迁移文件。 */
    static URI configuredServer(String[] args) {
        Map<String, String> config = Map.of();
        Path path = RuntimeFiles.peekConfigYamlPath();
        if (Files.isRegularFile(path)) {
            try {
                config = new ConfigFileEditor(path).readAll(List.of("server.port", "server.ssl.enabled", "ssl.domain"));
            } catch (IOException unreadable) {
                LOG.warn("Program key CLI could not read the configured server address: failureType={}",
                        unreadable.getClass().getSimpleName());
            }
        }
        String override = new SimpleCommandLinePropertySource(args).getProperty("server.port");
        String port = override != null ? override : config.get("server.port");
        int value;
        try {
            value = port == null || port.isBlank() ? 6999 : Integer.parseInt(port.trim());
        } catch (NumberFormatException invalid) {
            value = 6999;
        }
        Map<String, String> settings = config;
        return ServerAddressProvider.configuredBaseUri(settings::get, value);
    }

    private static Map<String, String> values(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            for (String flag : VALUE_FLAGS) {
                if (arg.startsWith(flag + "=")) values.put(flag, arg.substring(flag.length() + 1));
            }
        }
        return values;
    }

    private static List<String> tokens(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.split("[,\\s]+")).map(String::trim).filter(token -> !token.isEmpty()).toList();
    }

    private static String number(String token, List<String> catalog) {
        try {
            int index = Integer.parseInt(token);
            return index >= 1 && index <= catalog.size() ? catalog.get(index - 1) : null;
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private static String sources(JsonNode policy) {
        if (policy.path("anyIp").asBoolean(false)) return message("cli.keys.list.any-source");
        return String.join(", ", items(policy.path("networks")).stream().map(CliProgramKeys::text).toList());
    }

    private static List<JsonNode> items(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(values::add);
        return values;
    }

    private static String text(JsonNode node) {
        String value = node != null && node.isString() ? CliProgramKeyClient.displayText(node.stringValue()) : null;
        return value == null ? "" : value;
    }

    private static boolean loopback(String host) {
        if (host == null) return false;
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equalsIgnoreCase("localhost")) return true;
        if (!bare.matches("[0-9.]+|[0-9a-fA-F:]+")) return false;
        try {
            return InetAddress.getByName(bare).isLoopbackAddress();
        } catch (IOException invalid) {
            return false;
        }
    }

    private static int rejected(String action, String reason) {
        LOG.info("Program key CLI command rejected: action={}, reason={}", action, reason);
        return EXIT_INPUT;
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
