package top.sywyar.pixivdownload.plugin.api.work.query;

import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

/**
 * 作者目录查询条件。
 *
 * @param workType    媒体类型
 * @param restriction 访客限制投影；{@code null} 表示无限制（管理员 / 非访客）
 */
public record AuthorQuery(WorkType workType, WorkRestriction restriction) {
}
