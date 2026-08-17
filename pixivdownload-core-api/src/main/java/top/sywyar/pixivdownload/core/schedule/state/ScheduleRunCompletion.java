package top.sywyar.pixivdownload.core.schedule.state;

/**
 * 一轮无并发挂起冲突的完成写入。
 *
 * <p>checkpoint 与 outcome 在同一条 CAS 更新中提交；三个 checkpoint 字段必须同时为空或同时有值。
 * 全部为空表示保留既有 checkpoint，失败路径因此不会把上次安全断点清掉。
 */
public record ScheduleRunCompletion(
        long finishedTime,
        ScheduleLastOutcome outcome,
        String outcomeCode,
        String outcomeMessage,
        Long nextRunTime,
        String checkpointSchema,
        Integer checkpointVersion,
        String checkpointJson
) {
    /**
     * 创建 {@code ScheduleRunCompletion} 实例。
     *
     * @param finishedTime 完成时间
     * @param outcome 执行结果
     * @param outcomeCode 结果代码
     * @param outcomeMessage 结果消息
     * @param nextRunTime 下次值运行时间
     * @param checkpointSchema 检查点模式定义
     * @param checkpointVersion 检查点版本
     * @param checkpointJson 检查点JSON
     */
    public ScheduleRunCompletion {
        if (outcome != ScheduleLastOutcome.OK && outcome != ScheduleLastOutcome.ERROR) {
            throw new IllegalArgumentException("normal completion outcome must be OK or ERROR");
        }
        boolean noCheckpoint = checkpointSchema == null
                && checkpointVersion == null
                && checkpointJson == null;
        boolean completeCheckpoint = checkpointSchema != null
                && !checkpointSchema.isBlank()
                && checkpointVersion != null
                && checkpointVersion > 0
                && checkpointJson != null;
        if (!noCheckpoint && !completeCheckpoint) {
            throw new IllegalArgumentException("checkpoint schema, version and json must be supplied together");
        }
    }
}
