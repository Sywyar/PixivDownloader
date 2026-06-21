package top.sywyar.pixivdownload.plugin.api.event;

import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

/**
 * 作品本地文件被移动。事件骨架，字段随发布链路接入按需扩充。
 */
public record LocalFilesMovedEvent(WorkType workType, long workId) {
}
