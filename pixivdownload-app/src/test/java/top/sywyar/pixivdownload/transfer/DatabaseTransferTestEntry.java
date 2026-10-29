package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import java.nio.file.*;
import java.util.*;

/** 真正的可信 provider 装配与跨进程恢复，不启动业务后端。 */
public final class DatabaseTransferTestEntry {
    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        try (var instance = top.sywyar.pixivdownload.gui.SingleInstanceManager.acquire()) {
            if (instance == null) throw new IllegalStateException("INSTANCE_BUSY");
            if (args[0].equals("run")) { EnvironmentTransferService.runPending(); return; }
            var environment = DatabaseTarget.configurationEnvironment();
            try (var session = DatabaseBootstrap.openToolSession(environment)) {
                session.releaseStartupSnapshot();
                var config = new ConfigFileEditor(RuntimeFiles.peekConfigYamlPath()).asConfigFile();
                var settings = new DatabaseSettingsService(config, () -> environment, Optional::empty, session::databaseProviders);
                var host = (DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(DesktopUiHost.class.getClassLoader(),
                        new Class<?>[]{DesktopUiHost.class}, (proxy, method, parameters) -> switch (method.getName()) {
                            case "applicationVersion" -> "fixture";
                            case "applicationConfig" -> config;
                            case "databaseSettings" -> Optional.of(settings);
                            case "coreConfigFields" -> List.of(new GuiConfigFieldContribution(
                                    "server.port", "server", "fixture", GuiConfigFieldType.PORT, "", 1));
                            case "readPluginRepositories" -> List.of();
                            case "validateCoreConfigValue" -> null;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
                var transfer = new EnvironmentTransferService(host, List::of, () -> true, session, new String[0]);
                char[] password = "fixture archive secret".toCharArray();
                try {
                    if (args[0].equals("export")) transfer.exportConfiguration(Path.of(args[1]), password, false, true, true, false);
                    else {
                        var preview = transfer.previewConfiguration(Path.of(args[1]), password);
                        Files.write(Path.of(args[2]), new ObjectMapper().writeValueAsBytes(preview));
                        if (args[0].equals("prepare") || args[0].equals("prepare-paths")) transfer.importConfiguration(preview.token(),
                                preview.changes().stream().map(DesktopEnvironmentTransfer.Change::field).collect(java.util.stream.Collectors.toSet()),
                                Map.of(), password, true, preview.files().stream().map(DesktopEnvironmentTransfer.FileGroup::id)
                                        .collect(java.util.stream.Collectors.toSet()), args[0].equals("prepare-paths")
                                        ? Map.of(0L, Path.of("mapped-root").toAbsolutePath().toString(),
                                                7L, Path.of("mapped-prefix").toAbsolutePath().toString()) : Map.of());
                    }
                } finally { Arrays.fill(password, '\0'); }
            }
        }
    }
}
