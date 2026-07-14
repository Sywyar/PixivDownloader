package top.sywyar.pixivdownload.douyin.download;

import java.nio.file.Path;

public record DouyinDownloadedFile(
        Path path,
        long bytes,
        String contentType
) {

    public DouyinDownloadedFile(Path path, long bytes) {
        this(path, bytes, null);
    }
}
