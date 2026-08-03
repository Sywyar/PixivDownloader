package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;

/**
 * 单个调查的去重决策状态（持久化与响应共用）。所有时间戳由服务端生成。
 */
public record LayoutFeedbackStateEntry(
        String surveyId,
        LayoutFeedbackDecision status,
        long updatedAt,
        long snoozedUntil
) {

    public LayoutFeedbackStateEntry {
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            throw new IllegalArgumentException("invalid survey id");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (updatedAt < 0 || snoozedUntil < 0) {
            throw new IllegalArgumentException("invalid timestamps");
        }
    }
}
