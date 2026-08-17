package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.util.Objects;

/** 当前可见性作用域无权访问指定作品。HTTP 状态与用户文案由宿主 Web 层映射。 */
public final class WorkVisibilityDeniedException extends RuntimeException {

    /**
     * 工作类型。
     */
    private final WorkType workType;
    /**
     * 作品标识。
     */
    private final long workId;

    /**
     * 创建 {@code WorkVisibilityDeniedException} 实例。
     *
     * @param workType 工作类型
     * @param workId 作品标识
     */
    public WorkVisibilityDeniedException(WorkType workType, long workId) {
        super("Work is not visible: " + Objects.requireNonNull(workType, "workType") + " " + workId);
        this.workType = workType;
        this.workId = workId;
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
