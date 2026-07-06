package top.sywyar.pixivdownload.douyin.download;

import java.nio.file.Path;

public record DouyinDownloadedFile(
        Path path,
        long bytes
) {
}
