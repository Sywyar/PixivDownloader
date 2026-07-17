package top.sywyar.pixivdownload.core.archive;

/**
 * 核心管理员作品归档语义端口。
 */
public interface ArchiveExportService {

    /** 校验并返回规范化的归档格式 token；不支持时沿用宿主的本地化校验异常。 */
    String normalizeFormat(String format);

    ArchiveExportResult export(ArchiveExportRequest request);
}
