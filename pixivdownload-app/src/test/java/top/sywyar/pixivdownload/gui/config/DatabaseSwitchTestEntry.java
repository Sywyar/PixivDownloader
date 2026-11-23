package top.sywyar.pixivdownload.gui.config;

import tools.jackson.databind.json.JsonMapper;
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
            Map<String, String> draft = JsonMapper.builderWithJackson2Defaults().build().readValue(Files.readAllBytes(Path.of(args[1])),
                    new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
            var plan = migration.preview(draft);
            var competing = new DatabaseSwitchService(settings, config, session, () -> true);
            var competingPlan = args[0].equals("two-confirmations") ? competing.preview(draft) : null;
            Files.write(Path.of(args[2]), JsonMapper.builderWithJackson2Defaults().build().writeValueAsBytes(plan));
            if (!args[0].equals("preview") && plan.ready()) {
                if (args[0].equals("stale")) config.writeAll(Map.of("fixture.changed", "true"));
                migration.start(plan.token());
                if (competingPlan != null) {
                    byte[] journal = Files.readAllBytes(RuntimeFiles.databaseSwitchJournal());
                    try {
                        competing.start(competingPlan.token());
                        throw new AssertionError("Second confirmation replaced pending migration");
                    } catch (java.io.IOException expected) {
                        if (!"MIGRATION_BUSY".equals(expected.getMessage())) throw expected;
                    }
                    if (!Arrays.equals(journal, Files.readAllBytes(RuntimeFiles.databaseSwitchJournal())))
                        throw new AssertionError("Pending migration journal changed");
                }
            }
        }
    }
}
