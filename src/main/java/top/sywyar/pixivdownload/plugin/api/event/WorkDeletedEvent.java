package top.sywyar.pixivdownload.plugin.api.event;

import top.sywyar.pixivdownload.plugin.api.WorkType;

/**
 * 作品被删除（软删除标记）。事件骨架，字段随发布链路接入按需扩充。
 */
public record WorkDeletedEvent(WorkType workType, long workId) {
}
