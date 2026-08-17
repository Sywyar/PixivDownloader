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

    /**
     * 返回空的 {@code ArchiveExportResult} 实例。
     *
     * @return 方法返回的 {@code ArchiveExportResult} 实例
     */
    public static ArchiveExportResult empty() {
        return empty(0);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param workCount 作品数量
     * @return 方法返回的 {@code ArchiveExportResult} 实例
     */
    public static ArchiveExportResult empty(int workCount) {
        return new ArchiveExportResult(null, 0, workCount, 0);
    }

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public boolean emptyArchive() {
        return archiveToken == null || archiveToken.isBlank() || fileCount <= 0;
    }
}
