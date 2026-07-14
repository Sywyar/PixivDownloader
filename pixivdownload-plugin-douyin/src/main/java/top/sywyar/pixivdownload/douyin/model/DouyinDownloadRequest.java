package top.sywyar.pixivdownload.douyin.model;

import top.sywyar.pixivdownload.douyin.source.DouyinSourceRequest;

import java.util.List;

public record DouyinDownloadRequest(
        String input,
        String title,
        String cookie,
        String collectionId,
        String collectionTitle,
        String sourceType,
        String sourceId,
        String sourceTitle,
        String sourceUrl,
        Integer sourceOrder,
        List<DouyinSourceRequest> sourceRelations
) {

    public static final int MAX_SOURCE_RELATIONS = 128;

    public DouyinDownloadRequest {
        sourceRelations = sourceRelations == null ? List.of() : List.copyOf(sourceRelations);
        if (sourceRelations.size() > MAX_SOURCE_RELATIONS) {
            throw new IllegalArgumentException("Douyin source relations exceed count limit");
        }
    }

    public DouyinDownloadRequest(String input, String title, String cookie) {
        this(input, title, cookie, null, null, null, null, null, null, null, List.of());
    }

    public DouyinDownloadRequest(String input,
                                 String title,
                                 String cookie,
                                 String collectionId,
                                 String collectionTitle) {
        this(input, title, cookie, collectionId, collectionTitle,
                null, null, null, null, null, List.of());
    }

    public DouyinDownloadRequest(String input,
                                 String title,
                                 String cookie,
                                 String collectionId,
                                 String collectionTitle,
                                 String sourceType,
                                 String sourceId,
                                 String sourceTitle,
                                 String sourceUrl,
                                 Integer sourceOrder) {
        this(input, title, cookie, collectionId, collectionTitle,
                sourceType, sourceId, sourceTitle, sourceUrl, sourceOrder, List.of());
    }

    public DouyinDownloadRequest withCookie(String resolvedCookie) {
        return new DouyinDownloadRequest(input, title, resolvedCookie, collectionId, collectionTitle,
                sourceType, sourceId, sourceTitle, sourceUrl, sourceOrder, sourceRelations);
    }
}
