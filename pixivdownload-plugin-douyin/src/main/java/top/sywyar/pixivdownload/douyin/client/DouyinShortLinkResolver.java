package top.sywyar.pixivdownload.douyin.client;

import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;

public interface DouyinShortLinkResolver {

    DouyinParsedInput resolve(String input, String cookie) throws DouyinClientException;
}
