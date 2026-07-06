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
}
