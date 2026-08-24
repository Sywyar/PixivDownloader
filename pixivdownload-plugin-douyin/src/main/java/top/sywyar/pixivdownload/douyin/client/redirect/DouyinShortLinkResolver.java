package top.sywyar.pixivdownload.douyin.client.redirect;

import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedInput;

public interface DouyinShortLinkResolver {

    DouyinParsedInput resolve(String input, String cookie) throws DouyinClientException;
}
