package top.sywyar.pixivdownload.core.work.service;

import java.nio.file.Path;

/**
 * 下载作品目录的核心安全校验端口。
 */
public interface DownloadPathGuard {

    /**
     * 持久登记作品独占目录并创建它；调用方须先持有作品文件锁。
     * 失败或部分下载仍保留归属，后续同一作品可以重试。
     * @param type 作品类型
     * @param id 作品数字身份
     * @param directory 本次已经展开的最终目录
     * @throws java.io.IOException 目录无法安全创建
     * @throws top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException 目录与其它资产冲突
     */
    void prepareDirectory(top.sywyar.pixivdownload.core.work.model.WorkType type, long id, Path directory)
            throws java.io.IOException;

    /**
     * 校验候选目录段是否符合宿主路径安全规则。
     *
     * @throws DownloadPathRejectedException 候选目录段不符合宿主安全策略
     * @param value 值
     * @return 方法返回的字符串
     */
    String requireSafeDirectoryName(String value);

    /**
     * 校验候选路径是否位于指定下载根目录内。
     *
     * @throws DownloadPathRejectedException 候选路径不位于下载根目录内
     * @param root 根目录
     * @param candidate 候选项
     */
    void requireWithinRoot(Path root, Path candidate);

    /**
     * 查询实际下载目标的文件系统限制，不使用浏览器所在平台。
     * @param directory 作品目标目录
     * @return 可确认的文件名和路径长度限制
     */
    default DownloadPathLimits limits(Path directory) {
        return DownloadPathLimits.UNKNOWN;
    }

    /**
     * 为一次作品下载查询路径能力；只读探测，不创建文件，不把权限错误当作长度限制。
     * @param directory 作品目标目录
     * @return 判断候选路径是否满足已知长度限制的谓词
     */
    default java.util.function.Predicate<Path> pathSupport(Path directory) {
        return limits(directory)::accepts;
    }
}
