package top.sywyar.pixivdownload.tools;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

public final class ArtworksBackFill {
    private ArtworksBackFill() {}
    public static boolean supportsDatabaseColumn(String tableName, String columnName) {
        return SwingHost.host().supportsBackfillColumn(new DesktopUiHost.DatabaseColumn(tableName, columnName));
    }
    public static int countCandidates(Options options) throws Exception { return SwingHost.host().countBackfillCandidates(options.toHost()); }
    public static Summary run(Options options) throws Exception { return Summary.fromHost(SwingHost.host().runBackfill(options.toHost())); }
    public record DatabaseColumn(String tableName, String columnName) {}
    public record Options(String dbPath, String proxyHost, int proxyPort, boolean useProxy, long delayMs, int limit, boolean dryRun) {
        public static Options defaults() {
            DesktopUiHost.BackfillOptions value = SwingHost.host().defaultBackfillOptions();
            return new Options(value.dbPath(), value.proxyHost(), value.proxyPort(), value.useProxy(), value.delayMs(), value.limit(), value.dryRun());
        }
        DesktopUiHost.BackfillOptions toHost() { return new DesktopUiHost.BackfillOptions(dbPath, proxyHost, proxyPort, useProxy, delayMs, limit, dryRun); }
    }
    public record Summary(int totalCandidates, int processed, int filledAuthor, int filledR18, int filledAi,
                          int filledDescription, int filledTags, int filledSeries, int deletedCount, int skipped,
                          int previouslyUnreachable, int newlyUnreachable, boolean dryRun, boolean rateLimited) {
        static Summary fromHost(DesktopUiHost.BackfillSummary value) {
            return new Summary(value.totalCandidates(), value.processed(), value.filledAuthor(), value.filledR18(), value.filledAi(),
                    value.filledDescription(), value.filledTags(), value.filledSeries(), value.deletedCount(), value.skipped(),
                    value.previouslyUnreachable(), value.newlyUnreachable(), value.dryRun(), value.rateLimited());
        }
    }
}
