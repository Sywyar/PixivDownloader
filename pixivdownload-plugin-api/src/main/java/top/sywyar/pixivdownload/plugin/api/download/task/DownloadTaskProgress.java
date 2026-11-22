package top.sywyar.pixivdownload.plugin.api.download.task;

import java.util.Objects;

/**
 * 本次执行中某一步骤最近测得的进度。步骤达到总量时，任务仍可能处于执行中。
 * @param unit 计数共用的单位
 * @param completed 已处理数量
 * @param total 当前步骤已知的总量，必须为正数
 */
public record DownloadTaskProgress(Unit unit, long completed, long total) {
    /** 下载提供方报告的进度单位。 */
    public enum Unit {
        /** 请求的作品页。 */ PAGES,
        /** 当前步骤处理的图片。 */ IMAGES,
        /** 当前步骤传输的字节。 */ BYTES
    }

    /**
     * 记录总量已知的步骤进度。
     * @param unit 计数共用的单位
     * @param completed 已处理数量
     * @param total 当前步骤的正数总量
     */
    public DownloadTaskProgress {
        Objects.requireNonNull(unit, "unit");
        if (total <= 0 || completed < 0 || completed > total)
            throw new IllegalArgumentException("Invalid download progress");
    }
}
