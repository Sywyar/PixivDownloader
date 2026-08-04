package top.sywyar.pixivdownload.download.state;

/**
 * 服务端权威展示视图：由服务端按自己的时钟独立判断 Survey 状态是否到期，
 * 浏览器不参与解释任何服务端绝对时间点。
 *
 * <p>规则：
 * <ul>
 *   <li>state == null：status=null，canShow=true，retryAfterMs=0；</li>
 *   <li>SUBMITTED：status=submitted，canShow=false，retryAfterMs=0；</li>
 *   <li>NEVER：status=never，canShow=false，retryAfterMs=0；</li>
 *   <li>SNOOZED 且 serverNow &gt;= snoozedUntil：status=snoozed，canShow=true，
 *       retryAfterMs=0；</li>
 *   <li>SNOOZED 且 serverNow &lt; snoozedUntil：status=snoozed，canShow=false，
 *       retryAfterMs=snoozedUntil-serverNow（钳制到 JavaScript 安全整数）。</li>
 * </ul>
 *
 * <p>{@code serverNow} 由控制器在同一个请求中只读取一次时钟并钳制到非负值后传入；
 * retryAfterMs 不得为负、不得溢出，且必须能被 JavaScript 安全表示。
 */
public record LayoutFeedbackDecisionView(
        LayoutFeedbackDecision status,
        boolean canShow,
        long retryAfterMs
) {

    /** JavaScript 安全整数上限（Number.MAX_SAFE_INTEGER = 2^53 - 1）。 */
    public static final long MAX_SAFE_RETRY_AFTER_MS = 9_007_199_254_740_991L;

    /**
     * 按服务端当前时间评估权威展示视图。serverNow 必须已经过非负钳制
     * （{@code Math.max(0L, clock.millis())}）。
     */
    public static LayoutFeedbackDecisionView evaluate(
            LayoutFeedbackStateEntry state, long serverNow) {
        if (state == null) {
            return new LayoutFeedbackDecisionView(null, true, 0L);
        }
        switch (state.status()) {
            case SUBMITTED:
                return new LayoutFeedbackDecisionView(LayoutFeedbackDecision.SUBMITTED, false, 0L);
            case NEVER:
                return new LayoutFeedbackDecisionView(LayoutFeedbackDecision.NEVER, false, 0L);
            case SNOOZED:
                if (serverNow >= state.snoozedUntil()) {
                    return new LayoutFeedbackDecisionView(LayoutFeedbackDecision.SNOOZED, true, 0L);
                }
                // snoozedUntil > serverNow（两者都非负），差值不会溢出为负。
                long remaining = state.snoozedUntil() - serverNow;
                return new LayoutFeedbackDecisionView(
                        LayoutFeedbackDecision.SNOOZED, false,
                        Math.min(remaining, MAX_SAFE_RETRY_AFTER_MS));
            default:
                throw new IllegalStateException("unreachable decision");
        }
    }
}
