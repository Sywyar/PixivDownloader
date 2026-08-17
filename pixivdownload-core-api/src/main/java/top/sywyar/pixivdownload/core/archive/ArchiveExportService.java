package top.sywyar.pixivdownload.core.archive;

/**
 * 核心管理员作品归档语义端口。
 */
public interface ArchiveExportService {

    /**
     * 校验并返回规范化的归档格式 token；不支持时沿用宿主的本地化校验异常。
     *
     * @param format 格式
     * @return 方法返回的字符串
     */
    String normalizeFormat(String format);

    /**
     * 执行导出。
     *
     * @param request 请求
     * @return 方法返回的 {@code ArchiveExportResult} 实例
     */
    ArchiveExportResult export(ArchiveExportRequest request);
}
