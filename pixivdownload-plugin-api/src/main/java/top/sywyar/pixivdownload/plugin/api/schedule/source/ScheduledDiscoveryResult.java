package top.sywyar.pixivdownload.plugin.api.schedule.source;

/** 来源发现完成后返回的候选检查点；真正提交时机由宿主在作品排空与末尾 Guard 通过后决定。 */
public record ScheduledDiscoveryResult(ScheduledCheckpoint candidateCheckpoint) {

    public static ScheduledDiscoveryResult withoutCheckpoint() {
        return new ScheduledDiscoveryResult(null);
    }

    public static ScheduledDiscoveryResult withCheckpoint(ScheduledCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        return new ScheduledDiscoveryResult(checkpoint);
    }
}
