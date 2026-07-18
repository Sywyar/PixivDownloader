package top.sywyar.pixivdownload.push;

/**
 * 推送通道类型的稳定枚举。{@link #id()} 是配置键、协议投影与诊断中使用的稳定字符串标识，
 * 不随枚举常量改名而变化；具体配置存储由通道 owner 管理。
 * <p>
 * 新增通道时在此追加一个常量并实现对应的 {@link PushChannel}；中性派发器无需按通道添加分支。
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
