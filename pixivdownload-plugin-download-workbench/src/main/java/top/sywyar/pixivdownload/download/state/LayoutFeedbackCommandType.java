package top.sywyar.pixivdownload.download.state;

/**
 * 服务端状态命令枚举。所有时间戳都由服务端生成，客户端不得上传
 * {@code updatedAt} / {@code snoozedUntil} / {@code firstSeenAt} / {@code lastSeenAt}。
 */
public enum LayoutFeedbackCommandType {

    RECORD_SEEN("record_seen"),
    SNOOZE("snooze"),
    NEVER("never"),
    SUBMITTED("submitted");

    private final String wireName;

    LayoutFeedbackCommandType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 按线格式解析；未知命令返回 null（由调用方按 400 处理）。 */
    public static LayoutFeedbackCommandType fromWire(String wireName) {
        for (LayoutFeedbackCommandType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        return null;
    }
}
