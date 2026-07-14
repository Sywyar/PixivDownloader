package top.sywyar.pixivdownload.douyin.model;

public record DouyinCanonicalDownload(
        DouyinCanonicalKind kind,
        String stableId,
        String canonicalUrl,
        DouyinWork preResolvedWork,
        String originalInput
) {

    public DouyinCanonicalDownload {
        if (kind == null) {
            throw new IllegalArgumentException("Douyin canonical kind is required");
        }
        stableId = stableId == null ? "" : stableId.trim();
        canonicalUrl = canonicalUrl == null ? "" : canonicalUrl.trim();
        originalInput = originalInput == null ? "" : originalInput.trim();
        if (stableId.isBlank()) {
            throw new IllegalArgumentException("Douyin stable id is required");
        }
        if (kind == DouyinCanonicalKind.SINGLE_WORK && preResolvedWork == null) {
            throw new IllegalArgumentException("Single Douyin work must be pre-resolved");
        }
    }

    public String stableKey() {
        return switch (kind) {
            case SINGLE_WORK -> "work:" + stableId;
            case COLLECTION -> "collection:" + stableId;
            case USER_SOURCE -> "user:" + stableId;
        };
    }
}
