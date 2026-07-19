package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Objects;

/** 当前可见性作用域无权访问指定作品。HTTP 状态与用户文案由宿主 Web 层映射。 */
public final class WorkVisibilityDeniedException extends RuntimeException {

    private final WorkType workType;
    private final long workId;

    public WorkVisibilityDeniedException(WorkType workType, long workId) {
        super("Work is not visible: " + Objects.requireNonNull(workType, "workType") + " " + workId);
        this.workType = workType;
        this.workId = workId;
    }

    public WorkType workType() {
        return workType;
    }

    public long workId() {
        return workId;
    }
}
