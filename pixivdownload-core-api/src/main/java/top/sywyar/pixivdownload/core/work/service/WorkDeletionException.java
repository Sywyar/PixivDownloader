package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Objects;

/** 作品删除编排失败。HTTP 状态与用户文案由宿主 Web 层映射。 */
public final class WorkDeletionException extends RuntimeException {

    /**
     * 表示 {@code 该枚举值} 状态。
     */
    public enum Reason {
        /**
         * 原因。
         */
        LOCAL_FILE_DELETE_FAILED
    }

    /**
     * 原因。
     */
    private final Reason reason;
    /**
     * 工作类型。
     */
    private final WorkType workType;
    /**
     * 作品标识。
     */
    private final long workId;

    /**
     * 创建 {@code WorkDeletionException.Reason} 实例。
     *
     * @param reason 原因
     * @param workType 工作类型
     * @param workId 作品标识
     */
    public WorkDeletionException(Reason reason, WorkType workType, long workId) {
        super("Work deletion failed: " + Objects.requireNonNull(reason, "reason")
                + " " + Objects.requireNonNull(workType, "workType") + " " + workId);
        this.reason = reason;
        this.workType = workType;
        this.workId = workId;
    }

    /**
     * 返回原因。
     *
     * @return 方法返回的 {@code WorkDeletionException.Reason} 实例
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 返回作品类型。
     *
     * @return 方法返回的 {@code WorkType} 实例
     */
    public WorkType workType() {
        return workType;
    }

    /**
     * 返回作品标识。
     *
     * @return 方法返回的数值
     */
    public long workId() {
        return workId;
    }
}
