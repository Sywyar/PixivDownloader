package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化到 {@code state/download-workbench/layout-feedback-state.json} 的文档形态。
 *
 * <p>{@code schemaVersion=2} 按 Survey ID 保存多个独立状态（{@code states} 的 key 必须
 * 等于 entry.surveyId，且不得超过 {@link LayoutFeedbackStateStore#MAX_SURVEY_STATES}
 * 项）；{@code seen} 为下载工作台全局布局体验记录。revision 必须落在
 * {@code 0..MAX_SAFE_REVISION}（JavaScript 安全整数）。任何字段非法都视为
 * 损坏（按损坏文件处理）。
 *
 * <p>v1 / v2 状态字段严格互斥，字段存在即代表歧义协议，一律拒绝（不静默忽略另一版本
 * 的字段）：
 * <ul>
 *   <li>{@code schemaVersion=1}：{@code state} 可以为 null 或单个旧状态；
 *       {@code states} 必须为 null（含 {@code states: {}}）；state 非空时迁移进
 *       {@code states[state.surveyId]}，state 为空时迁移为空 states，seen / revision
 *       保留；</li>
 *   <li>{@code schemaVersion=2}：{@code state} 必须为 null；{@code states} 必填；</li>
 * </ul>
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
        if (revision < 0 || revision > LayoutFeedbackStateStore.MAX_SAFE_REVISION) {
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
            if (state != null) {
                throw new IllegalArgumentException("state is not allowed for schema version 2");
            }
            if (states == null) {
                throw new IllegalArgumentException("states is required for schema version 2");
            }
            if (states.size() > LayoutFeedbackStateStore.MAX_SURVEY_STATES) {
                throw new IllegalArgumentException("states exceeds MAX_SURVEY_STATES");
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
            // v1 兼容：state 可以为 null 或单个旧状态；states 字段出现即歧义，必须拒绝
            //（含 states: {}）。state 非空时迁移进 states[state.surveyId]；state 为空时
            // 迁移为空 states；seen / revision 保留。
            if (states != null) {
                throw new IllegalArgumentException("states is not allowed for schema version 1");
            }
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
