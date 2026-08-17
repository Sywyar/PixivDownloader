package top.sywyar.pixivdownload.core.work.service;

import java.nio.file.Path;

/**
 * 下载作品目录的核心安全校验端口。
 */
public interface DownloadPathGuard {

    /**
     * @throws DownloadPathRejectedException 候选目录段不符合宿主安全策略
     * @param value 值
     * @return 方法返回的字符串
     */
    String requireSafeDirectoryName(String value);

    /**
     * @throws DownloadPathRejectedException 候选路径不位于下载根目录内
     * @param root 根目录
     * @param candidate 候选项
     */
    void requireWithinRoot(Path root, Path candidate);
}
