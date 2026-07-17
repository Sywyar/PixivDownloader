package top.sywyar.pixivdownload.core.archive;

/**
 * 已受理的作品归档任务投影。
 */
public record ArchiveExportResult(
        String archiveToken,
        long archiveExpireSeconds,
        int workCount,
        int fileCount
) {

    public static ArchiveExportResult empty() {
        return empty(0);
    }

    public static ArchiveExportResult empty(int workCount) {
        return new ArchiveExportResult(null, 0, workCount, 0);
    }

    public boolean emptyArchive() {
        return archiveToken == null || archiveToken.isBlank() || fileCount <= 0;
    }
}
