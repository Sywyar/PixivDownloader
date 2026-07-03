package top.sywyar.pixivdownload.core.download.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * pixiv-batch 两阶段恢复用：当 GET verifyFiles 命中 fallback「裸记录」恢复（无元数据）后，
 * 前端调 Pixiv 拉回作品元数据，再 POST 给 /api/downloaded/{id}/recover-metadata 把缺的字段补齐。
 * 也支持「DB 完全无记录但磁盘已有文件」时直接写一条带 meta 的完整记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverMetadataRequest {
    private String title;
    private Long authorId;
    private String authorName;
    private Integer xRestrict;
    private Boolean isAi;
    private String description;
}
