package top.sywyar.pixivdownload.douyin.model.download;

public record DouyinStartResponse(
        boolean success,
        String id,
        String workId,
        String messageKey
) {
}
