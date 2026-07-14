package top.sywyar.pixivdownload.douyin.client;

import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;

public interface DouyinClient {

    DouyinCanonicalDownload resolveDownload(String input, String cookie) throws DouyinClientException;

    DouyinParsedInput resolveInput(String input, String cookie) throws DouyinClientException;

    DouyinWork resolvePublicWork(String input, String cookie) throws DouyinClientException;

    DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) throws DouyinClientException;

    DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) throws DouyinClientException;

    DouyinListing searchPublic(String word, int page, int pageSize, String cookie) throws DouyinClientException;

    default DouyinListing listUserWorksPage(String userId,
                                            String cursor,
                                            int limit,
                                            String cookie) throws DouyinClientException {
        int page = cursor == null || cursor.isBlank() || "0".equals(cursor.trim()) ? 1 : Integer.parseInt(cursor);
        return cursorFallback(listUserWorks(userId, Math.max(0, page - 1) * limit, limit, cookie), page);
    }

    default DouyinListing searchWorksPage(String word,
                                          String cursor,
                                          int limit,
                                          String cookie) throws DouyinClientException {
        int page = cursor == null || cursor.isBlank() || "0".equals(cursor.trim()) ? 1 : Integer.parseInt(cursor);
        return cursorFallback(searchPublic(word, page, limit, cookie), page);
    }

    default DouyinListing listSeriesWorksPage(String seriesId,
                                              String cursor,
                                              int limit,
                                              String cookie) throws DouyinClientException {
        int page = cursor == null || cursor.isBlank() || "0".equals(cursor.trim()) ? 1 : Integer.parseInt(cursor);
        return cursorFallback(listSeriesWorks(seriesId, page, limit, cookie), page);
    }

    default DouyinListing listMusicWorksPage(String musicId,
                                             String cursor,
                                             int limit,
                                             String cookie) throws DouyinClientException {
        throw unsupported("Douyin music listing is not available");
    }

    private static DouyinClientException unsupported(String message) {
        return new DouyinClientException(DouyinClientErrorCode.UNSUPPORTED_CONTENT, message);
    }

    private static DouyinListing cursorFallback(DouyinListing listing, int page) {
        if (listing == null || !listing.hasMore() || !listing.nextCursor().isBlank()) {
            return listing;
        }
        return new DouyinListing(listing.items(), listing.total(), listing.page(), listing.pageSize(),
                listing.lastPage(), listing.title(), listing.ownerId(), listing.ownerName(),
                Integer.toString(page + 1), true);
    }
}
