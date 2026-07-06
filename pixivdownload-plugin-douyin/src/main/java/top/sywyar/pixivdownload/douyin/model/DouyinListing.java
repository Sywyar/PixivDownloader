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
        String ownerName
) {

    public DouyinListing {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static DouyinListing empty(int page, int pageSize) {
        return new DouyinListing(List.of(), 0, page, pageSize, true, null, null, null);
    }
}
