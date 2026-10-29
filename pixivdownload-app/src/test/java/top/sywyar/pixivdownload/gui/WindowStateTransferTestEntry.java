package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferService;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用生产重启协调器及退出线程验证关闭写入和替代进程恢复。 */
public final class WindowStateTransferTestEntry {
    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        String scenario = args[0];
        Path root = Path.of(args[1]);
        if (scenario.equals("replacement")) {
            Files.writeString(root.resolve("replacement.pid"), Long.toString(ProcessHandle.current().pid()));
            ApplicationRestartService.awaitPreviousProcess();
            try (var instance = SingleInstanceManager.acquire()) {
                assertThat(instance).isNotNull();
                EnvironmentTransferService.runPending();
                Files.writeString(root.resolve("completed"), "predecessor-exited; instance-acquired");
            }
            return;
        }
        try (var instance = SingleInstanceManager.acquire()) {
            assertThat(instance).isNotNull();
            Files.createDirectories(RuntimeFiles.peekConfigYamlPath().getParent());
            Files.writeString(RuntimeFiles.peekConfigYamlPath(), "server.port: 7001\n");
            var window = new DesktopWindowStateStore(RuntimeFiles.guiStateDirectory());
            if (!scenario.equals("first-save"))
                assertThat(window.save(new DesktopUiHost.WindowStateSnapshot(1200, 800, false))).isTrue();
            char[] password = "fixture-password".toCharArray();
            Path bundle = root.resolve("settings.pdbackup");
            new TransferArchive().write(bundle, password, "fixture", List.of(
                    source(TransferArchive.Category.CONFIG, "settings.json", "{\"server.port\":\"8123\"}"),
                    source(TransferArchive.Category.STATE, "window/window-state.json", "{\"width\":1440,\"height\":960,\"maximized\":false}")));
            Runnable exit = () -> GuiLauncher.requestApplicationExit(() -> {
                try {
                    boolean saved = window.save(new DesktopUiHost.WindowStateSnapshot(1300, 800, false));
                    Files.writeString(root.resolve("close-save-result"), Boolean.toString(saved));
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }, () -> System.exit(0));
            var constructor = ApplicationRestartService.class.getDeclaredConstructor(
                    top.sywyar.pixivdownload.i18n.AppMessages.class, Supplier.class, Consumer.class, Runnable.class);
            constructor.setAccessible(true);
            Supplier<ProcessBuilder> factory = () -> command(root, "replacement")
                    .redirectErrorStream(true).redirectOutput(root.resolve("replacement.log").toFile());
            var restart = constructor.newInstance(null, factory, (Consumer<Runnable>) Runnable::run, exit);
            BooleanSupplier request = () -> {
                if (scenario.equals("restart-fails")) return false;
                if (scenario.equals("external-change")) {
                    try {
                        Files.writeString(RuntimeFiles.guiStateDirectory().resolve("window-state.json"),
                                "{\"width\":1500,\"height\":900,\"maximized\":false}");
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return restart.requestRestart();
            };
            var host = mock(DesktopUiHost.class);
            when(host.coreConfigFields()).thenReturn(List.of(new GuiConfigFieldContribution(
                    "server.port", "server", "fixture", GuiConfigFieldType.PORT, "", 1)));
            var transfer = new EnvironmentTransferService(host, List::of, request);
            var preview = transfer.previewConfiguration(bundle, password);
            try {
                transfer.importConfiguration(preview.token(), Set.of(new DesktopEnvironmentTransfer.FieldKey("host", "server.port")),
                        Map.of(), password, false, scenario.equals("unselected") ? Set.of()
                                : Set.of(new DesktopEnvironmentTransfer.FieldKey("host", "window")), Map.of());
                assertThat(scenario).isNotEqualTo("restart-fails");
            } catch (java.io.IOException failure) {
                if (!scenario.equals("restart-fails")) throw failure;
                assertThat(failure).hasMessage("transfer.restart-failed");
                exit.run();
            }
            assertThat(new java.util.concurrent.CountDownLatch(1).await(30, TimeUnit.SECONDS))
                    .as("exit thread must terminate the source process").isTrue();
        }
    }

    static ProcessBuilder command(Path root, String action) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8"));
        for (String name : List.of("config", "state", "data", "instance", "plugins"))
            command.add("-Dpixivdownload." + name + "-dir=" + root.resolve(name));
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WindowStateTransferTestEntry.class.getName(), action, root.toString()));
        return new ProcessBuilder(command).directory(root.toFile());
    }

    private static TransferArchive.Source source(TransferArchive.Category category, String path, String content) {
        return new TransferArchive.Source(new TransferArchive.Descriptor("host", category, path, 1),
                () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
