package top.sywyar.pixivdownload.migration;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.util.function.Consumer;

public final class JsonToSqliteMigration {
    private JsonToSqliteMigration() {}
    public static int countCandidates(Options options) throws Exception { return SwingHost.host().countMigrationCandidates(options.toHost()); }
    public static Summary run(Options options, Consumer<String> reporter) throws Exception {
        DesktopUiHost.MigrationSummary value = SwingHost.host().runMigration(options.toHost(), reporter);
        return new Summary(value.totalCandidates(), value.migrated(), value.skipped(), value.historyFileMissing(), value.message());
    }
    public record Options(String dbPath, String rootFolder) {
        DesktopUiHost.MigrationOptions toHost() { return new DesktopUiHost.MigrationOptions(dbPath, rootFolder); }
    }
    public record Summary(int totalCandidates, int migrated, int skipped, boolean historyFileMissing, String message) {}
}
