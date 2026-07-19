package top.sywyar.pixivdownload.core.work.query;

import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;

/**
 * 标签目录查询条件。
 *
 * @param workType    媒体类型
 * @param search      标签名 / 翻译名模糊匹配关键字，可为 {@code null}（不限）
 * @param limit       返回上限；具体钳制范围由实现沿用既有查询的语义
 * @param restriction 访客限制投影；{@code null} 表示无限制（管理员 / 非访客）
 */
public record TagQuery(WorkType workType, String search, int limit, WorkRestriction restriction) {
}
