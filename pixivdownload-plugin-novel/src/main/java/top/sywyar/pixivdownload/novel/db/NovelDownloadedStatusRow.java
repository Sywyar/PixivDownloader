package top.sywyar.pixivdownload.novel.db;

/** Lightweight downloaded-state projection used by batch status checks. */
public record NovelDownloadedStatusRow(long novelId, boolean deleted) {
}
