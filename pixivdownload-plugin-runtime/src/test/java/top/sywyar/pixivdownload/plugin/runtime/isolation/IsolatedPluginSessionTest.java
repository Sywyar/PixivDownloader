package top.sywyar.pixivdownload.plugin.runtime.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("隔离插件 worker 会话")
class IsolatedPluginSessionTest {

    private static final int MAX_STRING_BYTES = 128 * 1024;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_SET_ITEMS = 64;

    @TempDir
    Path tempDir;

    @AfterEach
    void clearWorkerProperties() {
        List.of(
                IsolatedPluginSession.INITIALIZE_TIMEOUT_PROPERTY,
                IsolatedPluginSession.COMMAND_TIMEOUT_PROPERTY,
                IsolatedPluginSession.SHUTDOWN_TIMEOUT_PROPERTY,
                IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY,
                IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY,
                IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY,
                IsolatedPluginSession.STDERR_MAX_BYTES_PROPERTY
        ).forEach(System::clearProperty);
    }

    @Test
    @DisplayName("worker 保留宿主环境并仅覆盖私有临时目录")
    void preservesInheritedEnvironment() {
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "tools-path");
        environment.put("HTTP_PROXY", "http://proxy.example:8080");
        environment.put("USERPROFILE", "user-profile");
        environment.put("LANG", "zh_CN.UTF-8");

        IsolatedPluginSession.configureEnvironment(builder, tempDir);

        assertThat(environment).containsEntry("PATH", "tools-path")
                .containsEntry("HTTP_PROXY", "http://proxy.example:8080")
                .containsEntry("USERPROFILE", "user-profile")
                .containsEntry("LANG", "zh_CN.UTF-8")
                .containsEntry("TEMP", tempDir.toString())
                .containsEntry("TMP", tempDir.toString())
                .containsEntry("TMPDIR", tempDir.toString());
    }

    @Test
    @DisplayName("worker 超时与指数退避配置有界且非法值失败关闭")
    void validatesWorkerSettingsAndCapsBackoff() {
        System.setProperty(IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY, "4");
        System.setProperty(IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY, "10");
        System.setProperty(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY, "25");

        IsolatedPluginSession.Settings settings = IsolatedPluginSession.Settings.fromSystemProperties();

        assertThat(settings.restartAttempts()).isEqualTo(4);
        assertThat(settings.restartDelay(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(settings.restartDelay(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(settings.restartDelay(3)).isEqualTo(Duration.ofMillis(25));

        System.setProperty(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY, "5");
        assertThatThrownBy(IsolatedPluginSession.Settings::fromSystemProperties)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY);
    }

    @Test
    @DisplayName("worker 命令失败通过有界协议保留异常类型与调用栈")
    void preservesWorkerFailureStackTrace() throws Exception {
        byte[] failure = IsolatedPluginProtocol.failure(new IllegalStateException("probe failure"));

        assertThatThrownBy(() -> IsolatedPluginProtocol.requireSuccess(failure))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("IllegalStateException: probe failure")
                .hasMessageContaining("preservesWorkerFailureStackTrace");
    }

    @Test
    @DisplayName("IPC 帧在字节上限边界内可往返并拒绝越界或截断输入")
    void enforcesFrameByteLimit() throws Exception {
        assertThat(roundTripFrame(IsolatedPluginProtocol.MAX_FRAME_BYTES - 1))
                .hasSize(IsolatedPluginProtocol.MAX_FRAME_BYTES - 1);
        assertThat(roundTripFrame(IsolatedPluginProtocol.MAX_FRAME_BYTES))
                .hasSize(IsolatedPluginProtocol.MAX_FRAME_BYTES);

        assertThatThrownBy(() -> writeFrame(new byte[IsolatedPluginProtocol.MAX_FRAME_BYTES + 1]))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid size");
        assertThatThrownBy(() -> readFrameWithDeclaredLength(
                IsolatedPluginProtocol.MAX_FRAME_BYTES + 1, new byte[0]))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid size");
        assertThatThrownBy(() -> readFrameWithDeclaredLength(3, new byte[]{1, 2}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("IPC 字符串在 UTF-8 字节上限边界内可往返并拒绝越界输入")
    void enforcesStringByteLimit() throws Exception {
        assertThat(roundTripString("a".repeat(MAX_STRING_BYTES - 1)))
                .hasSize(MAX_STRING_BYTES - 1);
        assertThat(roundTripString("a".repeat(MAX_STRING_BYTES)))
                .hasSize(MAX_STRING_BYTES);

        assertThatThrownBy(() -> writeString("a".repeat(MAX_STRING_BYTES + 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds the byte limit");
        assertThatThrownBy(() -> readStringWithDeclaredLength(MAX_STRING_BYTES + 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid size");
    }

    @Test
    @DisplayName("IPC 快照列表在项目数上限边界内可往返并拒绝越界输入")
    void enforcesSnapshotListLimit() throws Exception {
        assertThat(roundTripSnapshot(snapshotWithI18n(MAX_ITEMS - 1)).i18n())
                .hasSize(MAX_ITEMS - 1);
        assertThat(roundTripSnapshot(snapshotWithI18n(MAX_ITEMS)).i18n())
                .hasSize(MAX_ITEMS);

        assertThatThrownBy(() -> encodeSnapshot(snapshotWithI18n(MAX_ITEMS + 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("list has an invalid size");
        assertThatThrownBy(() -> decodeSnapshot(snapshotWithOversizedList()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("list has an invalid size");
    }

    @Test
    @DisplayName("IPC 快照集合在项目数上限边界内可往返并拒绝越界输入")
    void enforcesSnapshotSetLimit() throws Exception {
        assertThat(roundTripSnapshot(snapshotWithPlacements(MAX_SET_ITEMS - 1))
                .navigation().get(0).placements()).hasSize(MAX_SET_ITEMS - 1);
        assertThat(roundTripSnapshot(snapshotWithPlacements(MAX_SET_ITEMS))
                .navigation().get(0).placements()).hasSize(MAX_SET_ITEMS);

        assertThatThrownBy(() -> encodeSnapshot(snapshotWithPlacements(MAX_SET_ITEMS + 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("set has an invalid size");
        assertThatThrownBy(() -> decodeSnapshot(snapshotWithOversizedSet()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("set has an invalid size");
    }

    @Test
    @DisplayName("IPC 快照拒绝未知枚举与尾随字节且响应拒绝未知状态")
    void rejectsUnknownTokensAndTrailingBytes() throws Exception {
        assertThatThrownBy(() -> decodeSnapshot(snapshotWithUnknownAccessPolicy()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("AccessPolicy.NOT_A_POLICY");

        byte[] valid = encodeSnapshot(emptySnapshot());
        byte[] trailing = new byte[valid.length + 1];
        System.arraycopy(valid, 0, trailing, 0, valid.length);
        assertThatThrownBy(() -> decodeSnapshot(trailing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("trailing bytes");

        assertThatThrownBy(() -> IsolatedPluginProtocol.requireSuccess(new byte[]{2}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unknown response status");
    }

    @Test
    @DisplayName("强制清退 worker 时同时终止其后代进程")
    void terminatesWholeProcessTree() throws Exception {
        Process root = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessTreeRoot.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        long childPid;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                root.getInputStream(), StandardCharsets.UTF_8))) {
            childPid = Long.parseLong(reader.readLine());
        }

        assertThat(ProcessHandle.of(childPid)).hasValueSatisfying(handle -> assertThat(handle.isAlive()).isTrue());
        assertThat(IsolatedPluginSession.terminateProcessTree(root, Duration.ofSeconds(2))).isTrue();
        assertThat(root.isAlive()).isFalse();
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
                .toString();
    }

    private static byte[] roundTripFrame(int size) throws IOException {
        return IsolatedPluginProtocol.readFrame(new DataInputStream(
                new ByteArrayInputStream(writeFrame(new byte[size]))));
    }

    private static byte[] writeFrame(byte[] frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        IsolatedPluginProtocol.writeFrame(new DataOutputStream(bytes), frame);
        return bytes.toByteArray();
    }

    private static byte[] readFrameWithDeclaredLength(int length, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(length);
            output.write(payload);
        }
        return IsolatedPluginProtocol.readFrame(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static String roundTripString(String value) throws IOException {
        return IsolatedPluginProtocol.readString(new DataInputStream(
                new ByteArrayInputStream(writeString(value))));
    }

    private static byte[] writeString(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            IsolatedPluginProtocol.writeString(output, value);
        }
        return bytes.toByteArray();
    }

    private static String readStringWithDeclaredLength(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(length);
        }
        return IsolatedPluginProtocol.readString(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static IsolatedPluginProtocol.Snapshot snapshotWithI18n(int count) {
        List<I18nContribution> i18n = IntStream.range(0, count)
                .mapToObj(index -> new I18nContribution("namespace-" + index, "bundle-" + index, index))
                .toList();
        return new IsolatedPluginProtocol.Snapshot(List.of(), List.of(), i18n, List.of());
    }

    private static IsolatedPluginProtocol.Snapshot snapshotWithPlacements(int count) {
        Set<String> placements = IntStream.range(0, count)
                .mapToObj(index -> "placement-" + index)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        NavigationContribution navigation = new NavigationContribution(
                "navigation", placements, null, "nav.label", "/plugin/index.html", "puzzle",
                AccessPolicy.PUBLIC, 0, Set.of());
        return new IsolatedPluginProtocol.Snapshot(
                List.of(), List.of(), List.of(), List.of(navigation));
    }

    private static IsolatedPluginProtocol.Snapshot emptySnapshot() {
        return new IsolatedPluginProtocol.Snapshot(List.of(), List.of(), List.of(), List.of());
    }

    private static IsolatedPluginProtocol.Snapshot roundTripSnapshot(
            IsolatedPluginProtocol.Snapshot snapshot) throws IOException {
        return decodeSnapshot(encodeSnapshot(snapshot));
    }

    private static byte[] encodeSnapshot(IsolatedPluginProtocol.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            snapshot.writeTo(output);
        }
        return bytes.toByteArray();
    }

    private static IsolatedPluginProtocol.Snapshot decodeSnapshot(byte[] bytes) throws IOException {
        return IsolatedPluginProtocol.Snapshot.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static byte[] snapshotWithOversizedList() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAX_ITEMS + 1);
        }
        return bytes.toByteArray();
    }

    private static byte[] snapshotWithOversizedSet() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(1);
            IsolatedPluginProtocol.writeString(output, "navigation");
            output.writeInt(MAX_SET_ITEMS + 1);
        }
        return bytes.toByteArray();
    }

    private static byte[] snapshotWithUnknownAccessPolicy() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            IsolatedPluginProtocol.writeString(output, "/plugin/**");
            IsolatedPluginProtocol.writeString(output, "NOT_A_POLICY");
            output.writeInt(0);
            output.writeBoolean(false);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
        }
        return bytes.toByteArray();
    }

    public static final class ProcessTreeRoot {

        private ProcessTreeRoot() {
        }

        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeLeaf.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println(child.pid());
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }

    public static final class ProcessTreeLeaf {

        private ProcessTreeLeaf() {
        }

        public static void main(String[] args) throws Exception {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }
}
