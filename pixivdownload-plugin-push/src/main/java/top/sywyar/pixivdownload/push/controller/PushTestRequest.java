package top.sywyar.pixivdownload.push.controller;

import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.channel.bark.BarkSettings;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkSettings;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuSettings;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusSettings;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanSettings;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramSettings;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookSettings;
import top.sywyar.pixivdownload.push.channel.wecom.WecomSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 配置页"测试推送"按钮的请求 DTO。每个通道一个嵌套表单（对应 GUI 推送分组当前表单值），含各通道密钥
 * （用户尚未保存配置，需通过本地端点传给后端），仅在
 * {@link top.sywyar.pixivdownload.common.NetworkUtils#isTrustedLocalRequest} + GUI token 双校验后经同进程
 * localhost 流转。
 * <p>
 * {@link #toEnabledSettings()} 只收集 {@code enabled=true} 的通道，转成各通道的不可变设置快照交给
 * {@link top.sywyar.pixivdownload.push.PushService#test} 用临时设置发送，无需先落盘。
 */
public record PushTestRequest(Bark bark, Dingtalk dingtalk, Telegram telegram, Feishu feishu,
                              Wecom wecom, Pushplus pushplus, Serverchan serverchan, Webhook webhook) {

    public List<PushChannelSettings> toEnabledSettings() {
        List<PushChannelSettings> list = new ArrayList<>();
        if (bark != null && bark.enabled()) {
            list.add(bark.toSettings());
        }
        if (dingtalk != null && dingtalk.enabled()) {
            list.add(dingtalk.toSettings());
        }
        if (telegram != null && telegram.enabled()) {
            list.add(telegram.toSettings());
        }
        if (feishu != null && feishu.enabled()) {
            list.add(feishu.toSettings());
        }
        if (wecom != null && wecom.enabled()) {
            list.add(wecom.toSettings());
        }
        if (pushplus != null && pushplus.enabled()) {
            list.add(pushplus.toSettings());
        }
        if (serverchan != null && serverchan.enabled()) {
            list.add(serverchan.toSettings());
        }
        if (webhook != null && webhook.enabled()) {
            list.add(webhook.toSettings());
        }
        return list;
    }

    public record Bark(boolean enabled, String server, String deviceKey, String sound, boolean useProxy) {
        BarkSettings toSettings() {
            return new BarkSettings(server, deviceKey, sound, useProxy);
        }
    }

    public record Dingtalk(boolean enabled, String accessToken, String secret, boolean useProxy) {
        DingTalkSettings toSettings() {
            return new DingTalkSettings(accessToken, secret, useProxy);
        }
    }

    public record Telegram(boolean enabled, String botToken, String chatId, boolean useProxy) {
        TelegramSettings toSettings() {
            return new TelegramSettings(botToken, chatId, useProxy);
        }
    }

    public record Feishu(boolean enabled, String webhookKey, String secret, boolean useProxy) {
        FeishuSettings toSettings() {
            return new FeishuSettings(webhookKey, secret, useProxy);
        }
    }

    public record Wecom(boolean enabled, String key, boolean useProxy) {
        WecomSettings toSettings() {
            return new WecomSettings(key, useProxy);
        }
    }

    public record Pushplus(boolean enabled, String token, boolean useProxy) {
        PushPlusSettings toSettings() {
            return new PushPlusSettings(token, useProxy);
        }
    }

    public record Serverchan(boolean enabled, String sendKey, boolean useProxy) {
        ServerChanSettings toSettings() {
            return new ServerChanSettings(sendKey, useProxy);
        }
    }

    public record Webhook(boolean enabled, String url, String contentType, String bodyTemplate, boolean useProxy) {
        WebhookSettings toSettings() {
            return new WebhookSettings(url, contentType, bodyTemplate, useProxy);
        }
    }
}
