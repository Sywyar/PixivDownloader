package top.sywyar.pixivdownload.core.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理员作品归档请求。manifest 等附加内容由调用方作为普通字节条目提供。
 */
public record ArchiveExportRequest(
        List<ArchiveExportEntry> entries,
        String exportType,
        int workCount,
        int fileCount,
        String format,
        ArchiveWorkDeletion deleteAfterReady
) {

    /**
     * 创建 {@code ArchiveExportRequest} 实例。
     *
     * @param entries 条目列表
     * @param exportType 导出类型
     * @param workCount 作品数量
     * @param fileCount 文件数量
     * @param format 格式
     * @param deleteAfterReady {@code deleteAfterReady} 对应的值
     */
    public ArchiveExportRequest {
        entries = entries == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
