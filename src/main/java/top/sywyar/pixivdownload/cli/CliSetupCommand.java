package top.sywyar.pixivdownload.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.config.DefaultConfigTemplate;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.setup.SetupConfig;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 命令行管理工具：在无头服务器/Docker 等无法访问 GUI 与 setup 网页的环境下，
 * 通过启动参数完成首次初始化、修改/重置管理员密码等仅 GUI 提供的操作。
 *
 * <p>识别的命令（互斥，最多一个）：
 * <ul>
 *   <li>{@code --setup}                        首次初始化：用户名 + 密码 + 模式 (solo|multi)</li>
 *   <li>{@code --change-password}              修改管理员密码：需先校验当前密码</li>
 *   <li>{@code --reset-password}               重置管理员密码：不校验当前密码（适用于忘记原密码）</li>
 * </ul>
 *
 * <p>识别的可选 flag：{@code --username=}、{@code --password=}、{@code --old-password=}、
 * {@code --new-password=}、{@code --mode=solo|multi}。缺省时进入交互式模式从 stdin 读取。
 *
 * <p>调用方式：{@link #handleIfPresent(String[])} 检测命令并执行后调用 {@link System#exit(int)}，
 * 永不返回。{@link #isSetupComplete()} 供"无头未 setup 阻塞启动"逻辑使用。
 */
public final class CliSetupCommand {

    public static final String CMD_SETUP = "--setup";
    public static final String CMD_CHANGE_PASSWORD = "--change-password";
    public static final String CMD_RESET_PASSWORD = "--reset-password";

    public static final String FLAG_USERNAME = "--username";
    public static final String FLAG_PASSWORD = "--password";
    public static final String FLAG_OLD_PASSWORD = "--old-password";
    public static final String FLAG_NEW_PASSWORD = "--new-password";
    public static final String FLAG_MODE = "--mode";
    public static final String FLAG_PROXY_ENABLED = "--proxy-enabled";
    public static final String FLAG_PROXY_HOST = "--proxy-host";
    public static final String FLAG_PROXY_PORT = "--proxy-port";

    public static final String FLAG_HELP_LONG = "--help";
    public static final String FLAG_HELP_SHORT = "-h";

    private static final Set<String> COMMANDS = Set.of(CMD_SETUP, CMD_CHANGE_PASSWORD, CMD_RESET_PASSWORD);
    private static final Set<String> VALID_MODES = Set.of("solo", "multi");
    private static final int MIN_PASSWORD_LENGTH = 6;

    /**
     * 启动器自己识别的"无 value 标志"白名单。匹配这些字符串的参数会被原样放行。
     * Spring Boot 在 {@link org.springframework.boot.SpringApplication} 中也识别 {@code --debug} /
     * {@code --trace}（开启 debug/trace 日志），故一并白名单。
     */
    private static final Set<String> KNOWN_BOOL_FLAGS = Set.of(
            "--no-gui",
            "--intro",
            "--pixivdownload-startup",
            "--startup",
            CMD_SETUP,
            CMD_CHANGE_PASSWORD,
            CMD_RESET_PASSWORD,
            FLAG_HELP_LONG,
            FLAG_HELP_SHORT,
            "--debug",
            "--trace"
    );

    /**
     * 启动器自己识别的"必须带 value 的 flag 名"（以 {@code --name=value} 的形式出现）。
     * 仅用于在错误信息中提示用户「这个 flag 必须带值」。任何含 {@code =} 的参数都会被放行
     * 作为 Spring property override，无需在此显式列出。
     */
    private static final Set<String> KNOWN_VALUE_FLAGS = Set.of(
            FLAG_USERNAME,
            FLAG_PASSWORD,
            FLAG_OLD_PASSWORD,
            FLAG_NEW_PASSWORD,
            FLAG_MODE,
            FLAG_PROXY_ENABLED,
            FLAG_PROXY_HOST,
            FLAG_PROXY_PORT
    );

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CliSetupCommand() {
    }

    /**
     * 启动参数校验入口：
     * <ul>
     *   <li>命中 {@code --help} / {@code -h} → 打印帮助到 stdout 并以 0 退出</li>
     *   <li>含未识别参数 → 列出未识别项 + 帮助打印到 stderr，以 64 (EX_USAGE) 退出</li>
     *   <li>已识别但缺少必需值的 flag（如 {@code --username} 不带 {@code =}） → 同上</li>
     *   <li>否则正常返回</li>
     * </ul>
     * <p>放行规则：白名单 {@link #KNOWN_BOOL_FLAGS} 中的精确匹配、以及任何形如
     * {@code --key=value} 的参数（视为 Spring property override）。
     */
    public static void validateArgsOrExit(String[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        boolean helpRequested = false;
        List<String> unknown = new ArrayList<>();
        List<String> missingValue = new ArrayList<>();

        for (String arg : args) {
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            if (FLAG_HELP_LONG.equals(arg) || FLAG_HELP_SHORT.equals(arg)) {
                helpRequested = true;
                continue;
            }
            if (KNOWN_BOOL_FLAGS.contains(arg)) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq > 0 && arg.startsWith("--")) {
                // --key=value：放行（即便是未知 key 也按 Spring 属性覆盖处理）
                continue;
            }
            if (eq < 0 && KNOWN_VALUE_FLAGS.contains(arg)) {
                missingValue.add(arg);
                continue;
            }
            unknown.add(arg);
        }

        if (helpRequested) {
            printHelp(System.out);
            System.exit(0);
        }

        if (unknown.isEmpty() && missingValue.isEmpty()) {
            return;
        }

        for (String arg : unknown) {
            err(message("cli.error.unknown-arg", arg));
        }
        for (String arg : missingValue) {
            err(message("cli.error.flag-missing-value", arg, arg));
        }
        err("");
        printHelp(System.err);
        System.exit(64);
    }

    /**
     * 打印命令行帮助到指定流。表头包含版本、用法、通用选项、CLI 管理命令、
     * CLI 命令 flag、Spring 属性覆盖说明、示例与退出码。
     */
    public static void printHelp(PrintStream out) {
        if (out == null) {
            return;
        }
        String version = AppVersion.getDisplayVersionOrDefault(message("app.version.unknown"));
        out.println("PixivDownload " + version);
        out.println(message("cli.help.usage.heading"));
        out.println("  java -jar PixivDownload-vX.X.X.jar [OPTIONS]");
        out.println("  PixivDownload.exe [OPTIONS]");
        out.println();

        out.println(message("cli.help.section.general"));
        printOption(out, "--no-gui", "cli.help.opt.no-gui");
        printOption(out, "--intro", "cli.help.opt.intro");
        printOption(out, "--debug", "cli.help.opt.debug");
        printOption(out, "--trace", "cli.help.opt.trace");
        printOption(out, "--help, -h", "cli.help.opt.help");
        out.println();

        out.println(message("cli.help.section.cli-commands"));
        printOption(out, CMD_SETUP, "cli.help.opt.setup");
        printOption(out, CMD_CHANGE_PASSWORD, "cli.help.opt.change-password");
        printOption(out, CMD_RESET_PASSWORD, "cli.help.opt.reset-password");
        out.println();

        out.println(message("cli.help.section.cli-flags"));
        printOption(out, FLAG_USERNAME + "=NAME", "cli.help.opt.username");
        printOption(out, FLAG_PASSWORD + "=PWD", "cli.help.opt.password");
        printOption(out, FLAG_OLD_PASSWORD + "=PWD", "cli.help.opt.old-password");
        printOption(out, FLAG_NEW_PASSWORD + "=PWD", "cli.help.opt.new-password");
        printOption(out, FLAG_MODE + "=solo|multi", "cli.help.opt.mode");
        printOption(out, FLAG_PROXY_ENABLED + "=true|false", "cli.help.opt.proxy-enabled");
        printOption(out, FLAG_PROXY_HOST + "=HOST", "cli.help.opt.proxy-host");
        printOption(out, FLAG_PROXY_PORT + "=PORT", "cli.help.opt.proxy-port");
        out.println();

        out.println(message("cli.help.section.spring-override"));
        out.println("  " + message("cli.help.spring-override.desc"));
        out.println();

        out.println(message("cli.help.section.examples"));
        out.println("  java -jar app.jar --setup");
        out.println("  java -jar app.jar --setup --username=admin --password=secret123 --mode=solo");
        out.println("  java -jar app.jar --setup --username=admin --password=secret123 --mode=multi --proxy-enabled=false");
        out.println("  java -jar app.jar --change-password");
        out.println("  java -jar app.jar --reset-password");
        out.println("  java -jar app.jar --no-gui");
        out.println();

        out.println(message("cli.help.section.exit-codes"));
        printExitCode(out, "0", "cli.help.exit-code.success");
        printExitCode(out, "1", "cli.help.exit-code.operation-failed");
        printExitCode(out, "2", "cli.help.exit-code.bad-input");
        printExitCode(out, "64", "cli.help.exit-code.unknown-arg");
        printExitCode(out, "75", "cli.help.exit-code.another-instance");
        printExitCode(out, "78", "cli.help.exit-code.setup-required");
    }

    private static void printOption(PrintStream out, String flag, String descriptionCode) {
        // 23 列对齐 flag 后再打描述，宽度足够容纳最长的 --new-password=PWD（18 字符）+ 间距
        String padded = String.format("  %-22s  %s", flag, message(descriptionCode));
        out.println(padded);
    }

    private static void printExitCode(PrintStream out, String code, String descriptionCode) {
        String padded = String.format("  %3s  %s", code, message(descriptionCode));
        out.println(padded);
    }

    /**
     * 判断启动参数中是否含有任何已知的 CLI 管理命令。供 GuiLauncher 在锁取得失败时定制错误提示。
     */
    public static boolean containsCliCommand(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg != null && COMMANDS.contains(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在另一个实例已持有锁的情况下，针对 CLI 命令打印中止提示并以非零码退出。
     * 由 GuiLauncher 在单实例锁获取失败的分支调用，避免 CLI 命令被静默吞掉。
     */
    public static void abortBecauseAnotherInstanceRunning() {
        err(message("cli.error.another-instance-running"));
        err(message("cli.error.another-instance-running.hint"));
        System.exit(75);
    }

    /**
     * 若 {@code args} 中包含已知 CLI 命令，执行后通过 {@link System#exit(int)} 终止进程，
     * 不会返回；否则返回 {@code false} 让调用方继续常规启动流程。
     */
    public static boolean handleIfPresent(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }

        String command = null;
        for (String arg : args) {
            if (arg != null && COMMANDS.contains(arg)) {
                if (command != null && !command.equals(arg)) {
                    err(message("cli.error.conflicting-commands", command, arg));
                    System.exit(2);
                }
                command = arg;
            }
        }
        if (command == null) {
            return false;
        }

        Flags flags = Flags.parse(args);
        int exit;
        try {
            exit = switch (command) {
                case CMD_SETUP -> runSetup(flags);
                case CMD_CHANGE_PASSWORD -> runChangePassword(flags);
                case CMD_RESET_PASSWORD -> runResetPassword(flags);
                default -> 2;
            };
        } catch (Exception e) {
            err(message("cli.error.unexpected", safeMessage(e)));
            exit = 1;
        }
        System.exit(exit);
        return true;
    }

    /**
     * 读取 setup_config.json 并返回是否已完成初始化。文件缺失或读取失败时返回 {@code false}。
     */
    public static boolean isSetupComplete() {
        Path path = resolveSetupConfigPath();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            SetupConfig config = MAPPER.readValue(path.toFile(), SetupConfig.class);
            return config != null && config.isSetupComplete();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 当处于无头模式且 setup 未完成时，向 stderr 打印提示并以非零码退出。
     * GUI 模式下「首页」引导可完成首次配置，因此不阻拦。
     */
    public static void enforceSetupCompleteForHeadlessOrExit() {
        if (isSetupComplete()) {
            return;
        }
        Path path = resolveSetupConfigPath();
        err(message("cli.headless.setup-required.title"));
        err(message("cli.headless.setup-required.detail", path.toAbsolutePath()));
        err("");
        err(message("cli.headless.setup-required.hint"));
        err("    --setup");
        err("");
        err(message("cli.help.see-help"));
        System.exit(78);
    }

    // ── 命令实现 ───────────────────────────────────────────────────────────────────

    private static int runSetup(Flags flags) throws IOException {
        Path path = resolveSetupConfigPath();
        SetupConfig existing = loadOrEmpty(path);
        if (existing.isSetupComplete()) {
            err(message("cli.setup.already-complete", path.toAbsolutePath()));
            err(message("cli.setup.already-complete.hint"));
            return 1;
        }

        out(message("cli.setup.banner"));
        out(message("cli.setup.target-file", path.toAbsolutePath()));
        out("");

        String username = flags.username;
        if (username == null || username.isBlank()) {
            username = promptLine(message("cli.prompt.username"));
        }
        if (username == null || username.isBlank()) {
            err(message("cli.error.username-required"));
            return 2;
        }
        username = username.trim();

        String password = flags.password;
        if (password == null || password.isEmpty()) {
            password = promptPassword(message("cli.prompt.password"));
            if (password == null) {
                err(message("cli.error.password-required"));
                return 2;
            }
            String confirm = promptPassword(message("cli.prompt.password-confirm"));
            if (!password.equals(confirm)) {
                err(message("cli.error.password-mismatch"));
                return 2;
            }
        }
        String pwdError = validatePassword(password);
        if (pwdError != null) {
            err(pwdError);
            return 2;
        }

        String mode = flags.mode;
        if (mode == null || mode.isBlank()) {
            out("");
            out(message("cli.setup.mode.intro"));
            out(message("cli.setup.mode.solo.desc"));
            out(message("cli.setup.mode.multi.desc"));
            mode = promptLine(message("cli.prompt.mode"));
        }
        if (mode == null) {
            mode = "";
        }
        mode = mode.trim().toLowerCase(Locale.ROOT);
        if (!VALID_MODES.contains(mode)) {
            err(message("cli.error.invalid-mode", String.join(", ", VALID_MODES)));
            err(message("cli.setup.mode.solo.desc"));
            err(message("cli.setup.mode.multi.desc"));
            return 2;
        }

        // 代理配置（Docker 兼容：可关闭，或指向容器可达的代理地址）
        ProxyChoice proxy = resolveProxy(flags);
        if (proxy == null) {
            return 2;
        }

        SetupConfig updated = new SetupConfig();
        updated.setSetupComplete(true);
        updated.setUsername(username);
        updated.setPasswordHash(BCRYPT.encode(password));
        updated.setSalt(null);
        updated.setMode(mode);
        updated.setSessions(new LinkedHashMap<>());

        writeSetupConfig(path, updated);
        writeProxyConfig(proxy);
        out("");
        out(message("cli.setup.success", username, mode));
        out(message("cli.setup.proxy.summary",
                message(proxy.enabled() ? "cli.setup.proxy.on" : "cli.setup.proxy.off"),
                proxy.host(), proxy.port()));
        return 0;
    }

    /**
     * 解析代理配置：flag 优先，缺省时交互式询问。校验失败返回 null（调用方据此以 2 退出）。
     * 关闭代理时仍记录 host/port（flag 或默认值），便于后续开启复用。
     */
    private static ProxyChoice resolveProxy(Flags flags) {
        Boolean flagEnabled = parseBoolFlag(flags.proxyEnabled);
        if (flags.proxyEnabled != null && flagEnabled == null) {
            err(message("cli.error.invalid-proxy-enabled"));
            return null;
        }

        boolean enabled;
        if (flagEnabled != null) {
            enabled = flagEnabled;
        } else {
            out("");
            out(message("cli.setup.proxy.intro"));
            String answer = promptLine(message("cli.prompt.proxy-enable"));
            enabled = !isNegative(answer);  // 缺省启用
        }

        String host = flags.proxyHost;
        int port;
        if (enabled) {
            if (host == null || host.isBlank()) {
                String input = promptLine(message("cli.prompt.proxy-host", ProxyConfig.DEFAULT_HOST));
                host = (input == null || input.isBlank()) ? ProxyConfig.DEFAULT_HOST : input.trim();
            } else {
                host = host.trim();
            }
            String portText = flags.proxyPort;
            if (portText == null || portText.isBlank()) {
                String input = promptLine(message("cli.prompt.proxy-port", ProxyConfig.DEFAULT_PORT));
                portText = (input == null || input.isBlank())
                        ? Integer.toString(ProxyConfig.DEFAULT_PORT) : input.trim();
            }
            port = parsePort(portText);
            if (port < 1 || port > 65535) {
                err(message("cli.error.invalid-proxy-port"));
                return null;
            }
        } else {
            host = (host == null || host.isBlank()) ? ProxyConfig.DEFAULT_HOST : host.trim();
            int parsed = parsePort(flags.proxyPort);
            port = (parsed < 1 || parsed > 65535) ? ProxyConfig.DEFAULT_PORT : parsed;
        }
        return new ProxyChoice(enabled, host, port);
    }

    /**
     * 把代理配置写入 config.yaml（best-effort：失败仅警告，不影响已完成的初始化）。
     * <p>CLI 在 Spring 启动前运行，此时 config.yaml 可能尚未由 {@code AppConfigGenerator} 生成。
     * 为避免只写入 proxy.* 而其余配置缺失（导致下次启动 server.port 等回退到非预期默认值），
     * 文件不存在时先用 {@link DefaultConfigTemplate} 生成完整默认配置，再覆盖代理项。
     */
    private static void writeProxyConfig(ProxyChoice proxy) {
        try {
            Path configYaml = RuntimeFiles.resolveConfigYamlPath();
            if (!Files.exists(configYaml)) {
                Locale locale = Locale.getDefault();
                Files.createDirectories(configYaml.getParent());
                Files.writeString(configYaml,
                        DefaultConfigTemplate.build(code -> MessageBundles.get(locale, code)),
                        StandardCharsets.UTF_8);
            }
            ConfigFileEditor editor = new ConfigFileEditor(configYaml);
            Map<String, String> values = new LinkedHashMap<>();
            values.put(ProxyConfig.KEY_ENABLED, Boolean.toString(proxy.enabled()));
            values.put(ProxyConfig.KEY_HOST, proxy.host());
            values.put(ProxyConfig.KEY_PORT, Integer.toString(proxy.port()));
            editor.writeAll(values);
        } catch (IOException e) {
            err(message("cli.setup.proxy.write-failed", safeMessage(e)));
        }
    }

    private static int parsePort(String text) {
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 解析布尔型 flag 值：true/false/yes/no/y/n/1/0/on/off；无法识别返回 null。 */
    private static Boolean parseBoolFlag(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1", "on" -> Boolean.TRUE;
            case "false", "no", "n", "0", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** 交互式 [Y/n] 回答是否为「否」；空输入视为默认（是）。 */
    private static boolean isNegative(String answer) {
        if (answer == null) {
            return false;
        }
        return switch (answer.trim().toLowerCase(Locale.ROOT)) {
            case "n", "no", "false", "0", "off" -> true;
            default -> false;
        };
    }

    private record ProxyChoice(boolean enabled, String host, int port) {
    }

    private static int runChangePassword(Flags flags) throws IOException {
        Path path = resolveSetupConfigPath();
        SetupConfig config = loadOrEmpty(path);
        if (!config.isSetupComplete() || config.getUsername() == null || config.getPasswordHash() == null) {
            err(message("cli.change-password.setup-incomplete"));
            err(message("cli.change-password.setup-incomplete.hint"));
            return 1;
        }

        out(message("cli.change-password.banner", config.getUsername()));
        out(message("cli.setup.target-file", path.toAbsolutePath()));
        out("");

        String oldPassword = flags.oldPassword;
        if (oldPassword == null || oldPassword.isEmpty()) {
            oldPassword = promptPassword(message("cli.prompt.current-password"));
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            err(message("cli.error.password-required"));
            return 2;
        }
        if (!matches(oldPassword, config.getPasswordHash(), config.getSalt())) {
            err(message("cli.change-password.invalid-current"));
            return 1;
        }

        String newPassword = flags.newPassword;
        if (newPassword == null || newPassword.isEmpty()) {
            newPassword = promptPassword(message("cli.prompt.new-password"));
            if (newPassword == null) {
                err(message("cli.error.password-required"));
                return 2;
            }
            String confirm = promptPassword(message("cli.prompt.new-password-confirm"));
            if (!newPassword.equals(confirm)) {
                err(message("cli.error.password-mismatch"));
                return 2;
            }
        }
        String pwdError = validatePassword(newPassword);
        if (pwdError != null) {
            err(pwdError);
            return 2;
        }
        if (newPassword.equals(oldPassword)) {
            err(message("cli.change-password.same-password"));
            return 2;
        }

        config.setPasswordHash(BCRYPT.encode(newPassword));
        config.setSalt(null);
        // 修改密码后让所有现存 session 失效，与 SetupService.changePassword 一致
        config.setSessions(new LinkedHashMap<>());

        writeSetupConfig(path, config);
        out("");
        out(message("cli.change-password.success"));
        return 0;
    }

    private static int runResetPassword(Flags flags) throws IOException {
        Path path = resolveSetupConfigPath();
        SetupConfig config = loadOrEmpty(path);
        if (!config.isSetupComplete() || config.getUsername() == null || config.getPasswordHash() == null) {
            err(message("cli.change-password.setup-incomplete"));
            err(message("cli.change-password.setup-incomplete.hint"));
            return 1;
        }

        out(message("cli.reset-password.banner", config.getUsername()));
        out(message("cli.setup.target-file", path.toAbsolutePath()));
        out(message("cli.reset-password.warning"));
        out("");

        String newPassword = flags.newPassword;
        if (newPassword == null || newPassword.isEmpty()) {
            newPassword = promptPassword(message("cli.prompt.new-password"));
            if (newPassword == null) {
                err(message("cli.error.password-required"));
                return 2;
            }
            String confirm = promptPassword(message("cli.prompt.new-password-confirm"));
            if (!newPassword.equals(confirm)) {
                err(message("cli.error.password-mismatch"));
                return 2;
            }
        }
        String pwdError = validatePassword(newPassword);
        if (pwdError != null) {
            err(pwdError);
            return 2;
        }

        config.setPasswordHash(BCRYPT.encode(newPassword));
        config.setSalt(null);
        config.setSessions(new LinkedHashMap<>());

        writeSetupConfig(path, config);
        out("");
        out(message("cli.reset-password.success"));
        return 0;
    }

    // ── 持久化 ────────────────────────────────────────────────────────────────────

    private static SetupConfig loadOrEmpty(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new SetupConfig();
        }
        return MAPPER.readValue(path.toFile(), SetupConfig.class);
    }

    private static void writeSetupConfig(Path path, SetupConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), config);
    }

    private static Path resolveSetupConfigPath() {
        Path configYaml = RuntimeFiles.resolveConfigYamlPath();
        String rootFolder = RuntimeFiles.readDownloadRootFromConfig(configYaml, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        return RuntimeFiles.resolveSetupConfigPath(rootFolder);
    }

    // ── 密码校验（兼容旧 SHA-256 哈希） ─────────────────────────────────────────────

    private static boolean matches(String rawPassword, String storedHash, String legacySalt) {
        if (storedHash == null) {
            return false;
        }
        if (storedHash.startsWith("$2")) {
            return BCRYPT.matches(rawPassword, storedHash);
        }
        // 旧 SHA-256 兜底（与 SetupService.legacySha256Hash 等价）
        return storedHash.equals(legacySha256(rawPassword, legacySalt));
    }

    private static String legacySha256(String password, String salt) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(((salt == null ? "" : salt) + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            return message("cli.error.password-required");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return message("cli.error.password-too-short", MIN_PASSWORD_LENGTH);
        }
        return null;
    }

    // ── 输入/输出 ────────────────────────────────────────────────────────────────

    private static String promptLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("%s", prompt);
        }
        // 无 tty（IDE / pipe）：退回到 System.in
        System.out.print(prompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 读取一行密码，尽量不回显。无 {@link System#console()} 时（如 IDE）会回显并打印警告。
     */
    private static String promptPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("%s", prompt);
            return chars == null ? null : new String(chars);
        }
        System.err.println(message("cli.warn.password-echoed"));
        System.out.print(prompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static void out(String line) {
        PrintStream stream = System.out;
        if (stream != null) {
            stream.println(line);
        }
    }

    private static void err(String line) {
        PrintStream stream = System.err;
        if (stream != null) {
            stream.println(line);
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String message(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    // ── flag 解析 ─────────────────────────────────────────────────────────────────

    private static final class Flags {
        String username;
        String password;
        String oldPassword;
        String newPassword;
        String mode;
        String proxyEnabled;
        String proxyHost;
        String proxyPort;

        static Flags parse(String[] args) {
            Flags flags = new Flags();
            for (String arg : args) {
                if (arg == null) continue;
                flags.username = takeValue(arg, FLAG_USERNAME, flags.username);
                flags.password = takeValue(arg, FLAG_PASSWORD, flags.password);
                flags.oldPassword = takeValue(arg, FLAG_OLD_PASSWORD, flags.oldPassword);
                flags.newPassword = takeValue(arg, FLAG_NEW_PASSWORD, flags.newPassword);
                flags.mode = takeValue(arg, FLAG_MODE, flags.mode);
                flags.proxyEnabled = takeValue(arg, FLAG_PROXY_ENABLED, flags.proxyEnabled);
                flags.proxyHost = takeValue(arg, FLAG_PROXY_HOST, flags.proxyHost);
                flags.proxyPort = takeValue(arg, FLAG_PROXY_PORT, flags.proxyPort);
            }
            return flags;
        }

        private static String takeValue(String arg, String name, String current) {
            String prefix = name + "=";
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
            return current;
        }
    }
}
