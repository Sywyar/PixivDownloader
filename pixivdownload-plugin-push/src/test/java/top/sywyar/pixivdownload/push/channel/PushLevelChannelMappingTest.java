package top.sywyar.pixivdownload.push.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.OutboundRequest;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushHttpSender;
import top.sywyar.pixivdownload.push.PushLevel;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;
import top.sywyar.pixivdownload.push.TestMessageResolver;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;
import top.sywyar.pixivdownload.push.channel.bark.BarkPushChannel;
import top.sywyar.pixivdownload.push.channel.bark.BarkSettings;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuConfig;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuPushChannel;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuSettings;
import top.sywyar.pixivdownload.push.channel.wecom.WecomConfig;
import top.sywyar.pixivdownload.push.channel.wecom.WecomPushChannel;
import top.sywyar.pixivdownload.push.channel.wecom.WecomSettings;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送通道级别映射")
class PushLevelChannelMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Bark 将 ERROR 映射为 timeSensitive")
    void barkMapsErrorToTimeSensitive() throws Exception {
        CapturingSender sender = new CapturingSender();
        BarkPushChannel channel = new BarkPushChannel(new BarkConfig(), sender);

        channel.sendTest(
                new BarkSettings("https://api.day.app", "device-key", "", false),
                new RenderedMessage("标题", "正文", PushFormat.PLAIN_TEXT, PushLevel.ERROR));

        JsonNode root = JSON.readTree(sender.body());
        assertThat(root.path("level").asText()).isEqualTo("timeSensitive");
    }

    @Test
    @DisplayName("飞书卡片按 WARNING 映射橙色 header")
    void feishuMapsWarningToOrangeHeader() throws Exception {
        CapturingSender sender = new CapturingSender();
        FeishuPushChannel channel = new FeishuPushChannel(new FeishuConfig(), sender);

        channel.sendTest(
                new FeishuSettings("webhook-key", "", false),
                new RenderedMessage("标题", "正文", PushFormat.CARD, PushLevel.WARNING));

        JsonNode root = JSON.readTree(sender.body());
        assertThat(root.path("card").path("header").path("template").asText()).isEqualTo("orange");
    }

    @Test
    @DisplayName("企业微信 Markdown 标题按 ERROR 映射 warning 字体色")
    void wecomMapsErrorToWarningColor() throws Exception {
        CapturingSender sender = new CapturingSender();
        WecomPushChannel channel = new WecomPushChannel(new WecomConfig(), sender);

        channel.sendTest(
                new WecomSettings("wecom-key", false),
                new RenderedMessage("标题", "正文", PushFormat.MARKDOWN, PushLevel.ERROR));

        JsonNode root = JSON.readTree(sender.body());
        assertThat(root.path("markdown").path("content").asText())
                .contains("<font color=\"warning\">标题</font>");
    }

    private static final class CapturingSender extends PushHttpSender {
        private OutboundRequest request;

        private CapturingSender() {
            super(null, null, TestMessageResolver.INSTANCE);
        }

        @Override
        public PushResult send(PushChannelType type, OutboundRequest request) {
            this.request = request;
            return PushResult.ok(type);
        }

        private String body() {
            assertThat(request).as("通道应发出 outbound request").isNotNull();
            return new String(request.body(), StandardCharsets.UTF_8);
        }
    }
}
