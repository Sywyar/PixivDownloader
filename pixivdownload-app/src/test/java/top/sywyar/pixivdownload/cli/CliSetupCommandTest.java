package top.sywyar.pixivdownload.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.setup.SetupConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliSetupCommandTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("无头启动预检遵守初始化状态读取边界且不改写文件")
    void setupPrecheckUsesBoundedStateReader() throws Exception {
        String previousState = System.getProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        String previousConfig = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        Path state = Files.createDirectories(temp.resolve("state"));
        Path file = state.resolve("setup_config.json");
        try {
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, state.toString());
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, temp.resolve("config").toString());
            assertThat(CliSetupCommand.isSetupComplete()).isFalse();
            Files.writeString(file, "{\"setupComplete\":true}", StandardCharsets.UTF_8);
            assertThat(CliSetupCommand.isSetupComplete()).isTrue();
            Files.writeString(file, "{\"setupComplete\":true}" + " ".repeat(1024 * 1024),
                    StandardCharsets.UTF_8);
            byte[] oversized = Files.readAllBytes(file);
            assertThat(CliSetupCommand.isSetupComplete()).isFalse();
            assertThat(Files.readAllBytes(file)).isEqualTo(oversized);
            Files.writeString(file, "null", StandardCharsets.UTF_8);
            assertThat(CliSetupCommand.isSetupComplete()).isFalse();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("null");
        } finally {
            if (previousState == null) System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, previousState);
            if (previousConfig == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previousConfig);
        }
    }

    @Test
    @DisplayName("首次配置使用短密码时空回车确认并允许重新输入")
    void confirmsOrReplacesPasswordBelowRecommendation() {
        assertEquals("12345678", CliSetupCommand.confirmSetupPassword("12345678", () -> ""));
        assertEquals("123456789012",
                CliSetupCommand.confirmSetupPassword("12345678", () -> "123456789012"));

        AtomicInteger prompts = new AtomicInteger();
        assertEquals("abcdefgh",
                CliSetupCommand.confirmSetupPassword("12345678",
                        () -> prompts.getAndIncrement() == 0 ? "abcdefgh" : ""));
        assertEquals(2, prompts.get());
        assertNull(CliSetupCommand.confirmSetupPassword("12345678", () -> null));

        AtomicInteger strongPrompts = new AtomicInteger();
        assertEquals("123456789012",
                CliSetupCommand.confirmSetupPassword("123456789012", () -> {
                    strongPrompts.incrementAndGet();
                    return "";
                }));
        assertEquals(0, strongPrompts.get());
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("passwordCommands")
    @DisplayName("密码命令保持输入校验、独立认证、会话失效及失败时的原文件")
    void passwordCommandPreservesItsContract(String command, String scenario) throws Exception {
        String previousPassword = "previous-password";
        String replacement = "replacement-password";
        ObjectMapper mapper = new ObjectMapper();
        SetupConfig original = new SetupConfig();
        original.setSetupComplete(!scenario.equals("uninitialized"));
        original.setMode("solo");
        original.setUsername("fixture-admin");
        original.setDisplayName("fixture-name");
        original.setSalt("fixture-salt");
        original.setPasswordHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((original.getSalt() + previousPassword).getBytes(StandardCharsets.UTF_8))));
        original.setSessions(Map.of("fixture-session", 4102444800000L));
        Path state = Files.createDirectories(temp.resolve("state"));
        Path file = state.resolve("setup_config.json");
        byte[] before = mapper.writeValueAsBytes(original);
        Files.write(file, before);
        Path backup = state.resolve("setup_config.json.bak");
        if (scenario.equals("write-failure")) Files.createDirectory(backup);

        String supplied = switch (scenario) {
            case "interactive", "eof", "mismatch", "empty" -> null;
            case "same" -> previousPassword;
            case "short" -> "tiny";
            default -> replacement;
        };
        String input = switch (scenario) {
            case "interactive" -> replacement + "\n" + replacement + "\n";
            case "mismatch" -> replacement + "\ndifferent\n";
            case "empty" -> "\n\n";
            default -> "";
        };
        int expectedExit = switch (scenario) {
            case "eof", "mismatch", "empty", "short" -> 2;
            case "uninitialized", "write-failure" -> 1;
            case "wrong-old" -> command.equals("change-password") ? 1 : 0;
            case "same" -> command.equals("change-password") ? 2 : 0;
            default -> 0;
        };

        List<String> arguments = new ArrayList<>(List.of("--" + command,
                "--old-password=" + (scenario.equals("wrong-old") ? "incorrect" : previousPassword)));
        if (supplied != null) arguments.add("--new-password=" + supplied);
        assertThat(runCommand(arguments, input)).isEqualTo(expectedExit);
        if (expectedExit != 0) {
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
            return;
        }
        SetupConfig saved = mapper.readValue(file.toFile(), SetupConfig.class);
        assertThat(new BCryptPasswordEncoder().matches(supplied == null ? replacement : supplied,
                saved.getPasswordHash())).isTrue();
        assertThat(saved.getSalt()).isNull();
        assertThat(saved.getSessions()).isEmpty();
        assertThat(saved.isSetupComplete()).isTrue();
        assertThat(saved.getUsername()).isEqualTo(original.getUsername());
        assertThat(saved.getDisplayName()).isEqualTo(original.getDisplayName());
        assertThat(saved.getMode()).isEqualTo(original.getMode());
        assertThat(Files.readAllBytes(backup)).isEqualTo(before);
    }

    private static Stream<Arguments> passwordCommands() {
        return Stream.of("change-password", "reset-password").flatMap(command -> Stream.of(
                "flag", "interactive", "eof", "mismatch", "empty", "short", "same", "write-failure",
                "uninitialized", "wrong-old").map(scenario -> Arguments.of(command, scenario)));
    }

    private int runCommand(List<String> arguments, String input) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Dpixivdownload.config-dir=" + temp.resolve("config"),
                "-Dpixivdownload.state-dir=" + temp.resolve("state"), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CommandProbe.class.getName()));
        command.addAll(arguments);
        Path output = temp.resolve("command.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("密码命令应退出").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("测试子进程应结束").isTrue();
            }
        }
    }

    public static final class CommandProbe {
        public static void main(String[] args) {
            Utf8ConsoleStreams.install();
            CliSetupCommand.handleIfPresent(args);
        }
    }
}
