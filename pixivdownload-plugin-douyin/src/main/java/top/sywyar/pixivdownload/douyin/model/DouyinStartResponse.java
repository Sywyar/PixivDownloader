package top.sywyar.pixivdownload.douyin.model;

public record DouyinStartResponse(
        boolean success,
        String id,
        String workId,
        String messageKey
) {
}
