package top.sywyar.pixivdownload.douyin.model;

public record DouyinParsedInput(
        DouyinParsedKind kind,
        String originalInput,
        String originalUrl,
        String id,
        String canonicalUrl
) {
}
