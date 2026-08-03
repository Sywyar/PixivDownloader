package top.sywyar.pixivdownload.download.state;

/**
 * 调查去重决策状态。优先级固定为 {@code submitted > never > snoozed > null}，
 * 状态转移必须单调（见 {@link LayoutFeedbackStateStore}）。
 */
public enum LayoutFeedbackDecision {

    SUBMITTED("submitted"),
    NEVER("never"),
    SNOOZED("snoozed");

    private final String wireName;

    LayoutFeedbackDecision(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 按线格式解析；未知值返回 null（由调用方按 400 / 损坏处理）。 */
    public static LayoutFeedbackDecision fromWire(String wireName) {
        for (LayoutFeedbackDecision decision : values()) {
            if (decision.wireName.equals(wireName)) {
                return decision;
            }
        }
        return null;
    }
}
