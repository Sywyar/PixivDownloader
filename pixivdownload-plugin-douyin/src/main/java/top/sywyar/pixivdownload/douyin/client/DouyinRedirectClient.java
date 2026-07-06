package top.sywyar.pixivdownload.douyin.client;

import java.io.IOException;
import java.net.URI;

public interface DouyinRedirectClient {

    DouyinRedirectResponse get(URI uri, String cookie) throws IOException;
}
