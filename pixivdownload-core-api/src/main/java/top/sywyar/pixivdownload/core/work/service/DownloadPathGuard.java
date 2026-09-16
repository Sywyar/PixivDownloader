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

    /** 查询实际下载目标的文件系统限制，不使用浏览器所在平台。 */
    default DownloadPathLimits limits(Path directory) {
        return DownloadPathLimits.UNKNOWN;
    }

    /** 为一次作品下载查询路径能力；只读探测，不创建文件，不把权限错误当作长度限制。 */
    default java.util.function.Predicate<Path> pathSupport(Path directory) {
        return limits(directory)::accepts;
    }
}
