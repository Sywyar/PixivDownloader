package top.sywyar.pixivdownload.schedule;

/**
 * 检测到 Pixiv 过度访问警告时上抛，让 {@code runTaskAndRecord} 干净 unwind 本轮：
 * 标 {@code OVERUSE_PAUSED}、冻结同账号、发邮件。已派发的下载不回滚，水位线不推进。
 *
 * @see OveruseWarningService
 */
public class OveruseWarningException extends Exception {

    /** 触发本次暂停的警告 modifiedAt（毫秒），供 ack 放行与卡片展示。 */
    private final long modifiedAt;
    /** 警告正文摘要（已去 HTML、截断、绝不含凭证），供通知邮件展示。 */
    private final String excerpt;

    public OveruseWarningException(long modifiedAt, String excerpt) {
        super("overuse warning detected (modifiedAt=" + modifiedAt + ")");
        this.modifiedAt = modifiedAt;
        this.excerpt = excerpt == null ? "" : excerpt;
    }

    public long modifiedAt() {
        return modifiedAt;
    }

    public String excerpt() {
        return excerpt;
    }
}
