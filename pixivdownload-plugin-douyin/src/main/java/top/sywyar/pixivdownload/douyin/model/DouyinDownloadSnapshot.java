package top.sywyar.pixivdownload.douyin.model;

public record DouyinDownloadSnapshot(
        String id,
        String workId,
        DouyinDownloadPhase phase,
        boolean completed,
        boolean failed,
        boolean cancelled,
        String messageKey,
        String errorCode,
        String title,
        String fileName
) {
}
