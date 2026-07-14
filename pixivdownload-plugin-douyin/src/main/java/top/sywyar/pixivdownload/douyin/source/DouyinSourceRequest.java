package top.sywyar.pixivdownload.douyin.source;

public record DouyinSourceRequest(
        String sourceType,
        String sourceId,
        String sourceTitle,
        String sourceUrl,
        Integer sourceOrder
) {
}
