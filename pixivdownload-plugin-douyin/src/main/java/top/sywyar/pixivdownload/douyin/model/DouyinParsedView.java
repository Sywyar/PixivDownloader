package top.sywyar.pixivdownload.douyin.model;

public record DouyinParsedView(
        boolean supported,
        String kind,
        String id,
        String originalUrl,
        String canonicalUrl,
        String messageKey
) {

    public static DouyinParsedView unsupported(String messageKey) {
        return new DouyinParsedView(false, null, null, null, null, messageKey);
    }

    public static DouyinParsedView from(DouyinParsedInput input) {
        return new DouyinParsedView(
                true,
                input.kind().name(),
                input.id(),
                input.originalUrl(),
                input.canonicalUrl(),
                null);
    }
}
