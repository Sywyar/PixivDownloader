package top.sywyar.pixivdownload.push;

/**
 * 官方推送插件拥有的通道标识。核心只认识 {@link PushChannelId} 值契约，不枚举这里的具体标识。
 */
public final class PushChannelIds {

    public static final PushChannelId BARK = new PushChannelId("bark");
    public static final PushChannelId DINGTALK = new PushChannelId("dingtalk");
    public static final PushChannelId TELEGRAM = new PushChannelId("telegram");
    public static final PushChannelId FEISHU = new PushChannelId("feishu");
    public static final PushChannelId WECOM = new PushChannelId("wecom");
    public static final PushChannelId PUSHPLUS = new PushChannelId("pushplus");
    public static final PushChannelId SERVERCHAN = new PushChannelId("serverchan");
    public static final PushChannelId WEBHOOK = new PushChannelId("webhook");

    private PushChannelIds() {
    }
}
