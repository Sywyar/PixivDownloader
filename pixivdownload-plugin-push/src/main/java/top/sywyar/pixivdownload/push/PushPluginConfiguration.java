package top.sywyar.pixivdownload.push;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationPushTestController;
import top.sywyar.pixivdownload.notification.PushNotificationSink;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;
import top.sywyar.pixivdownload.push.channel.bark.BarkPushChannel;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkConfig;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkPushChannel;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuConfig;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuPushChannel;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusConfig;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusPushChannel;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanConfig;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanPushChannel;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramConfig;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramPushChannel;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookConfig;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookPushChannel;
import top.sywyar.pixivdownload.push.channel.wecom.WecomConfig;
import top.sywyar.pixivdownload.push.channel.wecom.WecomPushChannel;
import top.sywyar.pixivdownload.push.controller.PushTestController;

import java.util.function.Supplier;

@Configuration
public class PushPluginConfiguration {

    @Bean
    public PushPlugin pushPlugin() {
        return new PushPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushConfig pushConfig(Environment environment) {
        return bind(environment, "push", PushConfig::new, PushConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public MessageResolver pushPluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(messages, PushPlugin.class.getClassLoader(),
                "i18n.push.messages", "i18n.web.push");
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public BarkConfig barkConfig(Environment environment) {
        return bind(environment, "push.bark", BarkConfig::new, BarkConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public DingTalkConfig dingTalkConfig(Environment environment) {
        return bind(environment, "push.dingtalk", DingTalkConfig::new, DingTalkConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public TelegramConfig telegramConfig(Environment environment) {
        return bind(environment, "push.telegram", TelegramConfig::new, TelegramConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public FeishuConfig feishuConfig(Environment environment) {
        return bind(environment, "push.feishu", FeishuConfig::new, FeishuConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public WecomConfig wecomConfig(Environment environment) {
        return bind(environment, "push.wecom", WecomConfig::new, WecomConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushPlusConfig pushPlusConfig(Environment environment) {
        return bind(environment, "push.pushplus", PushPlusConfig::new, PushPlusConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public ServerChanConfig serverChanConfig(Environment environment) {
        return bind(environment, "push.serverchan", ServerChanConfig::new, ServerChanConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public WebhookConfig webhookConfig(Environment environment) {
        return bind(environment, "push.webhook", WebhookConfig::new, WebhookConfig.class);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushHttpSender pushHttpSender(@Qualifier("pushPluginMessages") MessageResolver messages,
                                         @Qualifier("pushRestTemplate") RestTemplate pushRestTemplate,
                                         @Qualifier("pushProxyRestTemplate") RestTemplate pushProxyRestTemplate) {
        return new PushHttpSender(pushRestTemplate, pushProxyRestTemplate, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushMessageFactory pushMessageFactory(@Qualifier("pushPluginMessages") MessageResolver messages) {
        return new PushMessageFactory(messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public BarkPushChannel barkPushChannel(BarkConfig config, PushHttpSender sender) {
        return new BarkPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public DingTalkPushChannel dingTalkPushChannel(DingTalkConfig config, PushHttpSender sender) {
        return new DingTalkPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public TelegramPushChannel telegramPushChannel(TelegramConfig config, PushHttpSender sender) {
        return new TelegramPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public FeishuPushChannel feishuPushChannel(FeishuConfig config, PushHttpSender sender) {
        return new FeishuPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public WecomPushChannel wecomPushChannel(WecomConfig config, PushHttpSender sender) {
        return new WecomPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushPlusPushChannel pushPlusPushChannel(PushPlusConfig config, PushHttpSender sender) {
        return new PushPlusPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public ServerChanPushChannel serverChanPushChannel(ServerChanConfig config, PushHttpSender sender) {
        return new ServerChanPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public WebhookPushChannel webhookPushChannel(WebhookConfig config, PushHttpSender sender) {
        return new WebhookPushChannel(config, sender);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushNotificationSink pushNotificationSink(PushConfig config,
                                                     PushMessageFactory messageFactory,
                                                     PushDispatcher pushDispatcher) {
        return new PushNotificationSink(config, messageFactory, pushDispatcher);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public PushTestController pushTestController(PushDispatcher pushDispatcher,
                                                 @Qualifier("pushPluginMessages") MessageResolver messages) {
        return new PushTestController(pushDispatcher, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public NotificationPushTestController notificationPushTestController(PushDispatcher pushDispatcher,
                                                                         PushMessageFactory messageFactory,
                                                                         @Qualifier("pushPluginMessages") MessageResolver messages) {
        return new NotificationPushTestController(pushDispatcher, messageFactory, messages);
    }

    private static <T> T bind(Environment environment, String prefix, Supplier<T> fallback, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }
}
