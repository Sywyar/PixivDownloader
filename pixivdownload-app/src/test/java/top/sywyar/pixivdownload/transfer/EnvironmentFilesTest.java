package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.FieldKey;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("环境文件的声明、只读预览与选择恢复")
class EnvironmentFilesTest {
    @TempDir Path root;
    private final Map<String, String> previous = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final TransferArchive archive = new TransferArchive();
    private final char[] password = "fixture-secret".toCharArray();

    @BeforeEach void saveProperties() {
        for (String property : List.of(RuntimeFiles.STATE_DIR_PROPERTY, RuntimeFiles.DATA_DIR_PROPERTY)) previous.put(property, System.getProperty(property));
    }
    @AfterEach void restoreProperties() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private void location(String name) {
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, root.resolve(name + "/state").toString());
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve(name + "/data").toString());
    }
    private void write(Path path, String text) throws IOException { Files.createDirectories(path.getParent()); Files.writeString(path, text); }

    @Test @DisplayName("管理员归档没有会话，导入保留目标模式并通过文件事务保存原内容")
    void restoresAdministratorWithoutSessionsOrChangingMode() throws Exception {
        location("source");
        write(RuntimeFiles.stateDirectory().resolve(RuntimeFiles.SETUP_CONFIG_JSON), """
                {"setupComplete":true,"mode":"solo","username":"source","passwordHash":"fixture-hash","sessions":{"source-token":7}}
                """);
        write(RuntimeFiles.stateDirectory().resolve("unrelated.exe"), "not-a-resource");
        var files = new EnvironmentFiles(null);
        Path bundle = root.resolve("environment.pdbackup");
        var snapshot = files.export(root.resolve("snapshot"), true, false, false, false);
        var inspected = archive.write(bundle, password, "fixture", snapshot);
        assertThat(inspected.items()).hasSize(1);
        try (var content = archive.readSelected(bundle, password, inspected, Set.of(0))) {
            assertThat(new String(content.get(0), StandardCharsets.UTF_8)).doesNotContain("source-token", "unrelated");
        }
        location("target");
        Path target = RuntimeFiles.stateDirectory().resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        write(target, "{\"setupComplete\":true,\"mode\":\"multi\",\"username\":\"target\",\"passwordHash\":\"old\",\"sessions\":{\"target-token\":9}}");
        byte[] before = Files.readAllBytes(target);
        var plan = files.preview(inspected);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(RuntimeFiles.dataDirectory()).doesNotExist();
        Path extracted = root.resolve("extracted");
        archive.extract(bundle, password, extracted, inspected);
        var targets = new LinkedHashMap<String, Path>();
        var replacements = new ArrayList<FileRestoreTransaction.Replacement>();
        files.render(plan, Set.of(new FieldKey("host", "administrator")), false, extracted,
                Files.createDirectory(root.resolve("staging")), targets, replacements);
        assertThat(files.resolveTargets(plan)).isEqualTo(targets);
        var transaction = new FileRestoreTransaction(root.resolve("transaction"), targets);
        transaction.prepare(replacements);
        transaction.commit();
        var restored = json.readTree(target.toFile());
        assertThat(restored.path("mode").textValue()).isEqualTo("multi");
        assertThat(restored.path("username").textValue()).isEqualTo("source");
        assertThat(restored.path("sessions").isEmpty()).isTrue();
        assertThat(Files.readAllBytes(root.resolve("transaction/0.before"))).isEqualTo(before);
    }

    @Test @DisplayName("图标须与数据库一起导入，预览之后的目标改动不被覆盖")
    void requiresDatabaseAndRejectsChangedTarget() throws Exception {
        location("source");
        write(RuntimeFiles.dataDirectory().resolve("collection_icons/12.png"), "image-bytes");
        var files = new EnvironmentFiles(null);
        Path bundle = root.resolve("icons.pdbackup");
        var inspected = archive.write(bundle, password, "fixture", files.export(root.resolve("snapshot"), false, true, false, true));
        location("target");
        var plan = files.preview(inspected);
        assertThat(root.resolve("target")).doesNotExist();
        Path extracted = root.resolve("extracted");
        archive.extract(bundle, password, extracted, inspected);
        Path staging = Files.createDirectory(root.resolve("staging"));
        var group = Set.of(new FieldKey("host", "collection-icons"));
        assertThatThrownBy(() -> files.render(plan, group, false, extracted, staging, new HashMap<>(), new ArrayList<>()))
                .isInstanceOf(IOException.class).hasMessage("transfer.files-require-database");
        Path target = RuntimeFiles.dataDirectory().resolve("collection_icons/12.png");
        write(target, "concurrent-edit");
        assertThatThrownBy(() -> files.render(plan, group, true, extracted, staging, new HashMap<>(), new ArrayList<>()))
                .isInstanceOf(IOException.class).hasMessage("transfer.target-changed");
        assertThat(Files.readString(target)).isEqualTo("concurrent-edit");
    }

    @Test @DisplayName("未安装所有者与不支持的文件声明不能选择或创建目标目录")
    void doesNotRestoreUnknownOwnersOrExecutableNames() throws Exception {
        location("target");
        var sources = List.of(source("not-installed", "resource/anything.dat"), source("host", "collection-icons/12.exe"));
        var inspected = archive.write(root.resolve("unknown.pdbackup"), null, "fixture", sources);
        var files = new EnvironmentFiles(null);
        var plan = files.preview(inspected);
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.unavailable()).hasSize(2);
        assertThat(root.resolve("target")).doesNotExist();
        assertThatThrownBy(() -> files.render(plan, Set.of(new FieldKey("host", "collection-icons")), true,
                root, root, new HashMap<>(), new ArrayList<>())).isInstanceOf(IOException.class);
    }

    private TransferArchive.Source source(String owner, String name) {
        return new TransferArchive.Source(new TransferArchive.Descriptor(owner, TransferArchive.Category.RESOURCE, name, 1),
                () -> new ByteArrayInputStream(new byte[]{1}));
    }

    @Test @DisplayName("管理员副本合并后目标模式变化，准备事务拒绝覆盖")
    void bindsFileMergeToItsOriginalTarget() throws Exception {
        location("source");
        write(RuntimeFiles.stateDirectory().resolve(RuntimeFiles.SETUP_CONFIG_JSON),
                "{\"setupComplete\":true,\"mode\":\"solo\",\"username\":\"source\",\"passwordHash\":\"hash\"}");
        var files = new EnvironmentFiles(null);
        Path bundle = root.resolve("administrator.pdbackup");
        var inspected = archive.write(bundle, password, "fixture",
                files.export(root.resolve("snapshot"), true, false, false, false));
        location("target");
        Path target = RuntimeFiles.stateDirectory().resolve(RuntimeFiles.SETUP_CONFIG_JSON);
        write(target, "{\"mode\":\"solo\",\"username\":\"target\"}");
        var plan = files.preview(inspected);
        Path extracted = root.resolve("extracted");
        archive.extract(bundle, password, extracted, inspected);
        var targets = new LinkedHashMap<String, Path>();
        var replacements = new ArrayList<FileRestoreTransaction.Replacement>();
        files.render(plan, Set.of(new FieldKey("host", "administrator")), false, extracted,
                Files.createDirectory(root.resolve("staging")), targets, replacements);
        write(target, "{\"mode\":\"multi\",\"username\":\"target\"}");
        byte[] latest = Files.readAllBytes(target);
        var transaction = new FileRestoreTransaction(root.resolve("run"), targets);
        assertThatThrownBy(() -> transaction.prepare(replacements))
                .isInstanceOf(IOException.class).hasMessage("transfer.target-changed");
        assertThat(Files.readAllBytes(target)).isEqualTo(latest);
    }

    @Test @DisplayName("真实参考音声明通过宿主归档完整迁移 WAV、MP3 和 PCM，仍要求数据库")
    void archivesEverySupportedReferenceVoiceFormat() throws Exception {
        Path classes = Path.of(System.getProperty("novel.plugin.classes"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var contribution = (top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution)
                    loader.loadClass("top.sywyar.pixivdownload.novel.narration.NarrationVoiceTransfer").getConstructor().newInstance();
            var plugin = mock(top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin.class);
            when(plugin.id()).thenReturn("novel");
            when(plugin.environmentFiles()).thenReturn(List.of(contribution));
            var session = mock(top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession.class);
            var manager = mock(top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.class);
            when(session.manager()).thenReturn(manager);
            var inventory = top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory.empty();
            when(manager.inspectPlugins()).thenReturn(inventory);
            when(manager.toDiscoveryResult(inventory)).thenReturn(new top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult(
                    List.of(new top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin("novel", "novel", plugin, loader)), List.of()));
            var files = new EnvironmentFiles(session);
            location("source");
            for (String extension : List.of("wav", "mp3", "pcm"))
                write(RuntimeFiles.dataDirectory().resolve("novel/narration-voice/7/0." + extension), "voice-" + extension);
            write(RuntimeFiles.dataDirectory().resolve("novel/narration-voice/7/0.exe"), "unrelated");
            assertThat(files.export(root.resolve("without-database"), false, false, false, true)).isEmpty();
            Path bundle = root.resolve("voices.pdbackup");
            var inspected = archive.write(bundle, null, "fixture", files.export(root.resolve("snapshot"), false, true, false, true));
            assertThat(inspected.items()).extracting(item -> item.descriptor().path()).containsExactlyInAnyOrder(
                    "reference-voices/7/0.wav", "reference-voices/7/0.mp3", "reference-voices/7/0.pcm");
            location("target");
            var plan = files.preview(inspected);
            var group = Set.of(new FieldKey("novel", "reference-voices"));
            Path extracted = root.resolve("extracted");
            archive.extract(bundle, null, extracted, inspected);
            Path staging = Files.createDirectory(root.resolve("staging"));
            assertThatThrownBy(() -> files.render(plan, group, false, extracted, staging, new HashMap<>(), new ArrayList<>()))
                    .hasMessage("transfer.files-require-database");
            var targets = new LinkedHashMap<String, Path>();
            var replacements = new ArrayList<FileRestoreTransaction.Replacement>();
            files.render(plan, group, true, extracted, staging, targets, replacements);
            var transaction = new FileRestoreTransaction(root.resolve("run"), targets);
            transaction.prepare(replacements);
            transaction.commit();
            for (String extension : List.of("wav", "mp3", "pcm"))
                assertThat(Files.readString(RuntimeFiles.dataDirectory().resolve("novel/narration-voice/7/0." + extension)))
                        .isEqualTo("voice-" + extension);
        }
    }

    @Test @DisplayName("窗口尺寸必须为整数，拒绝截断小数后静默导入")
    void rejectsFractionalWindowSize() throws Exception {
        var window = CoreEnvironmentFiles.contributions().stream()
                .filter(item -> item.definition().id().equals("window")).findFirst().orElseThrow();
        try (var source = new ByteArrayInputStream("{\"width\":1280.5,\"height\":900,\"maximized\":false}".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> window.copy("window-state.json", source, null, new ByteArrayOutputStream(), false))
                    .isInstanceOf(IOException.class).hasMessage("transfer.file-invalid");
        }
    }
}
