package top.sywyar.pixivdownload.push.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.TestMessageResolver;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;
import top.sywyar.pixivdownload.push.channel.bark.BarkPushChannel;
import top.sywyar.pixivdownload.push.channel.bark.BarkSettings;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkConfig;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkPushChannel;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkSettings;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuConfig;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuPushChannel;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuSettings;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusConfig;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusPushChannel;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusSettings;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanConfig;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanPushChannel;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanSettings;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramConfig;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramPushChannel;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramSettings;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookConfig;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookPushChannel;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookSettings;
import top.sywyar.pixivdownload.push.channel.wecom.WecomConfig;
import top.sywyar.pixivdownload.push.channel.wecom.WecomPushChannel;
import top.sywyar.pixivdownload.push.channel.wecom.WecomSettings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送通道故障安全契约")
class PushChannelBestEffortContractTest {

    @Test
    @DisplayName("全部官方通道在已保存与临时设置路径都收敛空渲染消息")
    void everyChannelContainsNullRenderedMessages() {
        for (ChannelCase item : channelCases()) {
            PushResult saved = item.channel().send(null);
            PushResult temporary = item.channel().sendTest(item.settings(), null);

            assertUnexpectedFailure(item.channel().type(), saved);
            assertUnexpectedFailure(item.channel().type(), temporary);
        }
    }

    private static List<ChannelCase> channelCases() {
        NoopSender sender = new NoopSender();

        BarkConfig bark = new BarkConfig();
        bark.setDeviceKey("bark-device-key");
        DingTalkConfig dingtalk = new DingTalkConfig();
        dingtalk.setAccessToken("dingtalk-access-token");
        TelegramConfig telegram = new TelegramConfig();
        telegram.setBotToken("telegram-bot-token");
        telegram.setChatId("telegram-chat-id");
        FeishuConfig feishu = new FeishuConfig();
        feishu.setWebhookKey("feishu-webhook-key");
        WecomConfig wecom = new WecomConfig();
        wecom.setKey("wecom-key");
        PushPlusConfig pushPlus = new PushPlusConfig();
        pushPlus.setToken("pushplus-token");
        ServerChanConfig serverChan = new ServerChanConfig();
        serverChan.setSendKey("serverchan-send-key");
        WebhookConfig webhook = new WebhookConfig();
        webhook.setUrl("https://example.test/hook/token");

        return List.of(
                new ChannelCase(new BarkPushChannel(bark, sender),
                        new BarkSettings("https://api.day.app", "bark-device-key", "", false)),
                new ChannelCase(new DingTalkPushChannel(dingtalk, sender),
                        new DingTalkSettings("dingtalk-access-token", "", false)),
                new ChannelCase(new TelegramPushChannel(telegram, sender),
                        new TelegramSettings("telegram-bot-token", "telegram-chat-id", false)),
                new ChannelCase(new FeishuPushChannel(feishu, sender),
                        new FeishuSettings("feishu-webhook-key", "", false)),
                new ChannelCase(new WecomPushChannel(wecom, sender),
                        new WecomSettings("wecom-key", false)),
                new ChannelCase(new PushPlusPushChannel(pushPlus, sender),
                        new PushPlusSettings("pushplus-token", false)),
                new ChannelCase(new ServerChanPushChannel(serverChan, sender),
                        new ServerChanSettings("serverchan-send-key", false)),
                new ChannelCase(new WebhookPushChannel(webhook, sender),
                        new WebhookSettings("https://example.test/hook/token",
                                "application/json", "", false)));
    }

    private static void assertUnexpectedFailure(PushChannelType type, PushResult result) {
        assertThat(result.channel()).isEqualTo(type);
        assertThat(result.status()).isEqualTo(PushResult.Status.FAILED);
        assertThat(result.detail()).isEqualTo(PushResult.DETAIL_UNEXPECTED_ERROR);
    }

    private record ChannelCase(PushChannel channel, PushChannelSettings settings) {
    }

    private static final class NoopSender extends PushHttpSender {
        private NoopSender() {
            super(null, null, TestMessageResolver.INSTANCE);
        }

        @Override
        public PushResult send(PushChannelType type, OutboundRequest request) {
            return PushResult.ok(type);
        }
    }
}
