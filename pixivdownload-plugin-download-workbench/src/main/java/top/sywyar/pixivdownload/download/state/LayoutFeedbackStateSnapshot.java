package top.sywyar.pixivdownload.download.state;

import java.util.Map;

/**
 * 服务端状态快照：revision + 当前（或最近一份）调查状态 + 独立于 Survey ID 的
 * 已体验布局清单。快照不可变，Map 一律 {@code Map.copyOf}，不得被原地修改。
 */
public record LayoutFeedbackStateSnapshot(
        long revision,
        LayoutFeedbackStateEntry state,
        Map<String, LayoutFeedbackSeenEntry> seen
) {

    public LayoutFeedbackStateSnapshot {
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
}
