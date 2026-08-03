package top.sywyar.pixivdownload.download.state;

/**
 * 单个布局的已体验记录。时间戳由服务端生成，不可由客户端传入。
 */
public record LayoutFeedbackSeenEntry(long firstSeenAt, long lastSeenAt) {

    public LayoutFeedbackSeenEntry {
        if (firstSeenAt < 0 || lastSeenAt < 0 || lastSeenAt < firstSeenAt) {
            throw new IllegalArgumentException("invalid seen timestamps");
        }
    }
}
