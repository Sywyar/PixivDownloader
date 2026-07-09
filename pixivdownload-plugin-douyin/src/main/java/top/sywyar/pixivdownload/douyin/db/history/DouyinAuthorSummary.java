package top.sywyar.pixivdownload.douyin.db.history;

public record DouyinAuthorSummary(
        String authorId,
        String name,
        long workCount
) {

    public DouyinAuthorSummary {
        authorId = blankToNull(authorId);
        name = blankToNull(name);
        workCount = Math.max(0, workCount);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
