package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Objects;

/** 作品删除编排失败。HTTP 状态与用户文案由宿主 Web 层映射。 */
public final class WorkDeletionException extends RuntimeException {

    public enum Reason {
        LOCAL_FILE_DELETE_FAILED
    }

    private final Reason reason;
    private final WorkType workType;
    private final long workId;

    public WorkDeletionException(Reason reason, WorkType workType, long workId) {
        super("Work deletion failed: " + Objects.requireNonNull(reason, "reason")
                + " " + Objects.requireNonNull(workType, "workType") + " " + workId);
        this.reason = reason;
        this.workType = workType;
        this.workId = workId;
    }

    public Reason reason() {
        return reason;
    }

    public WorkType workType() {
        return workType;
    }

    public long workId() {
        return workId;
    }
}
