package top.sywyar.pixivdownload.douyin.client;

import java.io.IOException;
import java.net.URI;

public interface DouyinRedirectClient {

    DouyinRedirectResponse get(URI uri) throws IOException, DouyinClientException;

    default DouyinRedirectResponse get(URI uri, String cookie) throws IOException, DouyinClientException {
        return get(uri);
    }
}
