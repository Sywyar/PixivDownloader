package top.sywyar.pixivdownload.douyin.model;

import java.util.List;

public record DouyinCollectionListing(
        List<DouyinCollectionSummary> items,
        int total,
        String nextCursor,
        boolean hasMore
) {
    public DouyinCollectionListing {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
