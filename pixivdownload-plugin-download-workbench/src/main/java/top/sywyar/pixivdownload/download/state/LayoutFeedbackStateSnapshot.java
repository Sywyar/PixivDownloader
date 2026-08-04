package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;

import java.util.Map;

/**
 * 服务端状态快照：revision + 按 Survey ID 隔离的状态表 + 独立于 Survey ID 的已体验
 * 布局清单。快照不可变，Map 一律防御复制（{@code Map.copyOf}），不得被原地修改。
 *
 * <p>states 的 key 必须与 entry.surveyId 严格一致；每个 Survey 独立保存状态，
 * 旧 Survey 标签页发送的动作不能覆盖新 Survey 状态。
 */
public record LayoutFeedbackStateSnapshot(
        long revision,
        Map<String, LayoutFeedbackStateEntry> states,
        Map<String, LayoutFeedbackSeenEntry> seen
) {

    public LayoutFeedbackStateSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("invalid revision");
        }
        if (states == null) {
            throw new IllegalArgumentException("states is required");
        }
        for (Map.Entry<String, LayoutFeedbackStateEntry> entry : states.entrySet()) {
            LayoutFeedbackStateEntry state = entry.getValue();
            if (state == null) {
                throw new IllegalArgumentException("null state for survey: " + entry.getKey());
            }
            if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(entry.getKey())
                    || !entry.getKey().equals(state.surveyId())) {
                throw new IllegalArgumentException("states key must equal entry surveyId");
            }
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
        states = Map.copyOf(states);
        seen = Map.copyOf(seen);
    }

    /** 当前 Survey 的状态（快照内与 seen 一致）。 */
    public LayoutFeedbackStateEntry state(String surveyId) {
        return states.get(surveyId);
    }
}
