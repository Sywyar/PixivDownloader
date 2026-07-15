package top.sywyar.pixivdownload.douyin.model.favorite;

import java.util.List;

public record DouyinFavoriteFolderListing(
        List<DouyinFavoriteFolderSummary> items,
        int total,
        String nextCursor,
        boolean hasMore
) {
    public DouyinFavoriteFolderListing {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
    }
}
