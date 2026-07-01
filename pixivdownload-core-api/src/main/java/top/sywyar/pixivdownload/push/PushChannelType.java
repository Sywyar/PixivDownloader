package top.sywyar.pixivdownload.push;

/**
 * 推送通道类型的稳定枚举。{@link #id()} 是该通道在 {@code config.yaml}（{@code push.<id>.*}）、日志与未来
 * GUI 中使用的稳定字符串标识，不随枚举常量改名而变化。
 * <p>
 * 新增通道时在此追加一个常量，并实现对应的 {@link PushChannel}（一个 {@code @Component}）即可——
 * {@link PushService} 通过 {@code List<PushChannel>} 自动发现，无需改动派发器。
 */
public enum PushChannelType {

    /** Bark（iOS 推送）。 */
    BARK("bark"),

    /** 钉钉自定义机器人。 */
    DINGTALK("dingtalk"),

    /** Telegram Bot。 */
    TELEGRAM("telegram"),

    /** 飞书自定义机器人。 */
    FEISHU("feishu"),

    /** 企业微信群机器人。 */
    WECOM("wecom"),

    /** PushPlus（推送加）。 */
    PUSHPLUS("pushplus"),

    /** Server 酱（Turbo / ³）。 */
    SERVERCHAN("serverchan"),

    /** 自定义 Webhook（用户自填 URL + body 模板）。 */
    WEBHOOK("webhook");

    private final String id;

    PushChannelType(String id) {
        this.id = id;
    }

    /** 配置 / 日志中使用的稳定标识（小写）。 */
    public String id() {
        return id;
    }
}
