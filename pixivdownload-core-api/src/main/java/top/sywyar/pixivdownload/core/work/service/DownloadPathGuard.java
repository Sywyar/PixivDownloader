package top.sywyar.pixivdownload.core.work.service;

import java.nio.file.Path;

/**
 * 下载作品目录的核心安全校验端口。
 */
public interface DownloadPathGuard {

    String requireSafeDirectoryName(String value);

    void requireWithinRoot(Path root, Path candidate);
}
