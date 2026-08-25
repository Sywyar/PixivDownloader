package top.sywyar.pixivdownload.douyin.model.input;

public record DouyinParsedInput(
        DouyinParsedKind kind,
        String originalInput,
        String originalUrl,
        String id,
        String canonicalUrl
) {
}
