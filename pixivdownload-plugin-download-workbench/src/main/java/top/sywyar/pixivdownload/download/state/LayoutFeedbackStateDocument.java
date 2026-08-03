package top.sywyar.pixivdownload.download.state;

import java.util.Map;

/**
 * 持久化到 {@code state/download-workbench/layout-feedback-state.json} 的文档形态。
 * {@code schemaVersion} 固定为 1；任何字段非法都视为损坏（按损坏文件处理）。
 */
public record LayoutFeedbackStateDocument(
        int schemaVersion,
        long revision,
        LayoutFeedbackStateEntry state,
        Map<String, LayoutFeedbackSeenEntry> seen
) {

    public LayoutFeedbackStateDocument {
        if (schemaVersion != LayoutFeedbackStateStore.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema version");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("invalid revision");
        }
        if (seen == null) {
            throw new IllegalArgumentException("seen is required");
        }
        for (Map.Entry<String, LayoutFeedbackSeenEntry> entry : seen.entrySet()) {
            if (!LayoutFeedbackStateStore.LAYOUT_IDS.contains(entry.getKey())
                    || entry.getValue() == null) {
                throw new IllegalArgumentException("invalid seen layout: " + entry.getKey());
            }
        }
        seen = Map.copyOf(seen);
    }

    public LayoutFeedbackStateSnapshot toSnapshot() {
        return new LayoutFeedbackStateSnapshot(revision, state, seen);
    }
}
