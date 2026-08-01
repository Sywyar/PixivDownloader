package top.sywyar.pixivdownload.core.work.service;

import java.nio.file.Path;

/**
 * 下载作品目录的核心安全校验端口。
 */
public interface DownloadPathGuard {

    /**
     * @throws DownloadPathRejectedException 候选目录段不符合宿主安全策略
     */
    String requireSafeDirectoryName(String value);

    /**
     * @throws DownloadPathRejectedException 候选路径不位于下载根目录内
     */
    void requireWithinRoot(Path root, Path candidate);
}
