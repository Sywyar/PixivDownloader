package top.sywyar.pixivdownload.douyin.model;

import java.util.List;

public record DouyinListing(
        List<DouyinWork> items,
        int total,
        int page,
        int pageSize,
        boolean lastPage,
        String title,
        String ownerId,
        String ownerName,
        String nextCursor,
        boolean hasMore
) {

    public DouyinListing {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
        if (lastPage) {
            hasMore = false;
        }
    }

    public DouyinListing(List<DouyinWork> items,
                         int total,
                         int page,
                         int pageSize,
                         boolean lastPage,
                         String title,
                         String ownerId,
                         String ownerName) {
        this(items, total, page, pageSize, lastPage, title, ownerId, ownerName, "", !lastPage);
    }

    public static DouyinListing empty(int page, int pageSize) {
        return new DouyinListing(List.of(), 0, page, pageSize, true, null, null, null, "", false);
    }
}
