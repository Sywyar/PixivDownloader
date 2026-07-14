package top.sywyar.pixivdownload.douyin.db.history;

public record DouyinSourceRelation(
        String workId,
        String sourceType,
        String sourceId,
        String sourceTitle,
        String sourceUrl,
        Integer sourceOrder,
        long discoveredTime
) {
    public DouyinSourceRelation {
        workId = required(workId, "workId");
        sourceType = required(sourceType, "sourceType");
        sourceId = required(sourceId, "sourceId");
        sourceTitle = clean(sourceTitle);
        sourceUrl = clean(sourceUrl);
        discoveredTime = discoveredTime > 0 ? discoveredTime : System.currentTimeMillis();
    }

    public DouyinSourceRelation withWorkId(String stableWorkId) {
        return new DouyinSourceRelation(stableWorkId, sourceType, sourceId, sourceTitle,
                sourceUrl, sourceOrder, discoveredTime);
    }

    private static String required(String value, String field) {
        String clean = clean(value);
        if (clean == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return clean;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
