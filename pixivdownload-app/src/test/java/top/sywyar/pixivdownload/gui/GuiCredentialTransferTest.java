package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.OwnerPluginCredentials;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferService;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("无 GUI 凭据经生产目录、加密归档与替代进程恢复")
public class GuiCredentialTransferTest {
    @TempDir Path root;
    private static final String OWNER = "headless-owner";
    private static final String KEY = OWNER + ".token";
    private static final char[] PASSWORD = "archive-fixture".toCharArray();

    private static PluginRegistry registry() {
        var plugin = new PixivFeaturePlugin() {
            public String id() { return OWNER; }
            public String displayName() { return "fixture"; }
            public String description() { return "fixture"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<String> credentialKeys() { return List.of(KEY); }
        };
        return new PluginRegistry(List.of(), new PluginToggleProperties(), new PluginDiscoveryResult(List.of(
                new DiscoveredFeaturePlugin(OWNER, OWNER, plugin, plugin.getClass().getClassLoader())), List.of()));
    }

    private static EnvironmentTransferService transfer() {
        var host = mock(DesktopUiHost.class);
        when(host.applicationVersion()).thenReturn("fixture");
        var registry = registry();
        return new EnvironmentTransferService(host, () -> GuiLauncher.buildTransferPluginSnapshots(registry), () -> true);
    }

    @Test @DisplayName("程序化写入的无 GUI token 可包含或排除导出，源进程退出后恢复为目标密文")
    void transfersHeadlessCredentialAcrossProcesses() throws Exception {
        Map<String, String> previous = new HashMap<>();
        for (String name : List.of("config", "data", "state", "instance", "plugins")) {
            String property = "pixivdownload." + name + "-dir";
            previous.put(property, System.getProperty(property));
            System.setProperty(property, Files.createDirectories(root.resolve("source/" + name)).toString());
        }
        try {
            var registry = registry();
            assertThat(GuiLauncher.buildDesktopUiPluginSnapshots(GuiLauncher.buildDesktopUiPluginSources(registry)))
                    .singleElement().satisfies(snapshot -> assertThat(snapshot.configContributions()).isEmpty());
            var store = new PluginCredentialStore();
            var tasks = new PluginRuntimeTaskRegistry();
            new OwnerPluginCredentials(store, new PluginCredentialDefinitionResolver(registry))
                    .create(OWNER, tasks.resourcesForPlugin(OWNER)).update(Map.of(KEY, "headless-secret"));
            Path source = RuntimeFiles.peekPluginCredentialPath(OWNER);
            byte[] sourceBytes = Files.readAllBytes(source);
            var transfer = transfer();
            Path ordinary = root.resolve("ordinary.pdbackup");
            transfer.exportConfiguration(ordinary, null, false, false, false, false);
            assertThat(new TransferArchive().inspect(ordinary, null).items()).noneMatch(item ->
                    item.descriptor().category() == TransferArchive.Category.CREDENTIALS);
            Path bundle = root.resolve("credentials.pdbackup");
            transfer.exportConfiguration(bundle, PASSWORD, true, false, false, false);
            assertThat(new String(Files.readAllBytes(bundle), StandardCharsets.ISO_8859_1)).doesNotContain("headless-secret");
            assertThatThrownBy(() -> new TransferArchive().inspect(bundle, "wrong-password".toCharArray()))
                    .isInstanceOf(java.io.IOException.class);
            var preview = new TransferArchive().inspect(bundle, PASSWORD);
            assertThat(preview.items()).anyMatch(item -> item.descriptor().owner().equals(OWNER)
                    && item.descriptor().category() == TransferArchive.Category.CREDENTIALS);
            runChild("prepare", bundle);
            Path restored = root.resolve("target/config/credentials/" + OWNER + ".properties");
            assertThat(restored).doesNotExist();
            runChild("run", bundle);
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, root.resolve("target/config").toString());
            assertThat(store.readForTransfer(OWNER)).containsExactlyEntriesOf(Map.of(KEY, "headless-secret"));
            assertThat(Files.readString(restored)).doesNotContain("headless-secret", KEY);
            assertThat(RuntimeFiles.peekPluginConfigPath(OWNER)).doesNotExist();
            assertThat(Files.readAllBytes(source)).isEqualTo(sourceBytes);
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private void runChild(String action, Path bundle) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8"));
        for (String name : List.of("config", "data", "state", "instance", "plugins"))
            command.add("-Dpixivdownload." + name + "-dir=" + root.resolve("target/" + name));
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                GuiCredentialTransferTest.class.getName(), action, bundle.toString()));
        Path log = root.resolve(action + ".log");
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        try (var instance = SingleInstanceManager.acquire()) {
            if (instance == null) throw new IllegalStateException("INSTANCE_BUSY");
            if (args[0].equals("run")) { EnvironmentTransferService.runPending(); return; }
            var transfer = transfer();
            var preview = transfer.previewConfiguration(Path.of(args[1]), PASSWORD);
            assertThat(preview.changes()).anyMatch(change -> change.field().equals(new DesktopEnvironmentTransfer.FieldKey(OWNER, KEY)));
            assertThat(preview.toString()).doesNotContain("headless-secret");
            transfer.importConfiguration(preview.token(), preview.changes().stream().map(DesktopEnvironmentTransfer.Change::field)
                    .collect(Collectors.toSet()), Map.of(), PASSWORD, false, Set.of(), Map.of());
        }
    }
}
