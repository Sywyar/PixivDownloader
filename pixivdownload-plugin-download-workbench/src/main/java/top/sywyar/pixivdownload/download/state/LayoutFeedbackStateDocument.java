package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化到 {@code state/download-workbench/layout-feedback-state.json} 的文档形态。
 *
 * <p>{@code schemaVersion=2} 按 Survey ID 保存多个独立状态（{@code states} 的 key 必须
 * 等于 entry.surveyId）；{@code seen} 为下载工作台全局布局体验记录。任何字段非法都视为
 * 损坏（按损坏文件处理）。{@code state} 字段只用于兼容读取 v1 文档（单个状态），写入
 * 一律输出 v2 形态。
 *
 * <p>文件不包含 distinctId / canShow / retryAfterMs / serverTime / PostHog 配置 /
 * 用户建议。
 */
public record LayoutFeedbackStateDocument(
        int schemaVersion,
        long revision,
        LayoutFeedbackStateEntry state,
        Map<String, LayoutFeedbackStateEntry> states,
        Map<String, LayoutFeedbackSeenEntry> seen
) {

    public LayoutFeedbackStateDocument {
        if (schemaVersion != 2 && schemaVersion != 1) {
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
        Map<String, LayoutFeedbackStateEntry> merged = new LinkedHashMap<>();
        if (schemaVersion == 2) {
            if (states == null) {
                throw new IllegalArgumentException("states is required for schema version 2");
            }
            for (Map.Entry<String, LayoutFeedbackStateEntry> entry : states.entrySet()) {
                LayoutFeedbackStateEntry value = entry.getValue();
                if (value == null) {
                    throw new IllegalArgumentException("null state for survey: " + entry.getKey());
                }
                if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(entry.getKey())
                        || !entry.getKey().equals(value.surveyId())) {
                    throw new IllegalArgumentException("states key must equal entry surveyId");
                }
                merged.put(entry.getKey(), value);
            }
        } else {
            // v1 兼容：单个 state 迁移进 states[state.surveyId]；seen / revision 保留。
            if (state != null) {
                merged.put(state.surveyId(), state);
            }
        }
        states = Map.copyOf(merged);
        state = null;
    }

    public LayoutFeedbackStateSnapshot toSnapshot() {
        return new LayoutFeedbackStateSnapshot(revision, states, seen);
    }
}
