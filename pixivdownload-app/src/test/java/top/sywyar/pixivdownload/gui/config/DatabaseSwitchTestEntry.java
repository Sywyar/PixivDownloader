package top.sywyar.pixivdownload.gui.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap;
import java.nio.file.*;
import java.util.*;

/** 子进程夹具用耐久任务交接，不在测试 JVM 内伪装已退出的原应用。 */
public final class DatabaseSwitchTestEntry {
    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        if (args[0].equals("run")) {
            try (var instance = top.sywyar.pixivdownload.gui.SingleInstanceManager.acquire()) {
                if (instance == null) throw new IllegalStateException("INSTANCE_BUSY");
                DatabaseSwitchService.runPending(new String[0]);
            }
            return;
        }
        var environment = DatabaseTarget.configurationEnvironment();
        try (var session = DatabaseBootstrap.openToolSession(environment)) {
            session.releaseStartupSnapshot();
            var config = new ConfigFileEditor(RuntimeFiles.peekConfigYamlPath()).asConfigFile();
            var active = DatabaseTarget.fromBinding(DatabaseBootstrap.bind(session, environment));
            var settings = new DatabaseSettingsService(config, () -> environment, () -> Optional.of(active), session::databaseProviders);
            var migration = new DatabaseSwitchService(settings, config, session, () -> !args[0].equals("restart-fails"));
            Map<String, String> draft = new ObjectMapper().readValue(Files.readAllBytes(Path.of(args[1])),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            var plan = migration.preview(draft);
            Files.write(Path.of(args[2]), new ObjectMapper().writeValueAsBytes(plan));
            if (!args[0].equals("preview") && plan.ready()) {
                if (args[0].equals("stale")) config.writeAll(Map.of("fixture.changed", "true"));
                migration.start(plan.token());
            }
        }
    }
}
