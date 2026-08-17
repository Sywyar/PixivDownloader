package top.sywyar.pixivdownload.plugin.api.schedule.source;

/**
 * 来源发现完成后返回的候选检查点；真正提交时机由宿主在作品排空与末尾 Guard 通过后决定。候选检查点不得包含
 * 原始凭据或可逆派生材料。
 */
public record ScheduledDiscoveryResult(ScheduledCheckpoint candidateCheckpoint) {

    /**
     * 返回移除检查点后的副本。
     *
     * @return 方法返回的 {@code ScheduledDiscoveryResult} 实例
     */
    public static ScheduledDiscoveryResult withoutCheckpoint() {
        return new ScheduledDiscoveryResult(null);
    }

    /**
     * 返回更新检查点后的副本。
     *
     * @param checkpoint 检查点
     * @return 方法返回的 {@code ScheduledDiscoveryResult} 实例
     */
    public static ScheduledDiscoveryResult withCheckpoint(ScheduledCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        return new ScheduledDiscoveryResult(checkpoint);
    }
}
