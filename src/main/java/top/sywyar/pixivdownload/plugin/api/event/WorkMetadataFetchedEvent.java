package top.sywyar.pixivdownload.plugin.api.event;

import top.sywyar.pixivdownload.plugin.api.WorkType;

/**
 * 作品元数据抓取/刷新完成。事件骨架，字段随发布链路接入按需扩充。
 */
public record WorkMetadataFetchedEvent(WorkType workType, long workId) {
}
