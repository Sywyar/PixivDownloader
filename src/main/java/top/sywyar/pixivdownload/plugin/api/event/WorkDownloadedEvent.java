package top.sywyar.pixivdownload.plugin.api.event;

import top.sywyar.pixivdownload.plugin.api.WorkType;

/**
 * 作品下载完成（全部页成功落库后）。事件骨架，字段随发布链路接入按需扩充。
 */
public record WorkDownloadedEvent(WorkType workType, long workId) {
}
