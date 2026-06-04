package top.sywyar.pixivdownload.config;

import java.util.Locale;
import java.util.function.Function;

/**
 * config.yaml 默认内容的唯一模板来源。
 * <p>{@link AppConfigGenerator}（Spring 启动时生成 / 补全）与 CLI {@code --setup}（无头首次初始化、
 * 需在 Spring 启动前补齐完整配置文件）都通过本类构建默认内容，避免模板结构在两处各写一份而漂移。
 * 文案解析方式由调用方注入（{@code Function<code, text>}），因此本类不依赖任何 Spring bean。
 */
public final class DefaultConfigTemplate {

    static final String COMMENT_PREFIX = "# ";
    private static final String HEADER_SEPARATOR = "========================================================";
    private static final int CONFIG_ENTRY_WIDTH = 45;

    private DefaultConfigTemplate() {
    }

    /** 把以 "# " 开头的注释行文本拼出来，供生成器在追加缺失项时复用。 */
    public static String comment(String text) {
        return COMMENT_PREFIX + text;
    }

    /**
     * 构建完整的默认 config.yaml 文本。
     *
     * @param messages 文案解析器：传入 i18n key（如 {@code config.template.server.port.comment}）返回本地化文案
     */
    public static String build(Function<String, String> messages) {
        StringBuilder config = new StringBuilder();

        appendComment(config, HEADER_SEPARATOR);
        appendComment(config, messages.apply("config.template.header.title"));
        appendComment(config, messages.apply("config.template.header.restart-required"));
        appendComment(config, HEADER_SEPARATOR);
        appendBlankLine(config);

        appendSetting(config, messages, "server.port: 6999", "config.template.server.port.comment");
        appendSetting(config, messages, "debug.enabled: false", "config.template.debug.enabled.comment");
        appendBlankLine(config);

        appendSetting(config, messages, "download.root-folder: pixiv-download", "config.template.download.root-folder.comment");
        appendSetting(config, messages, "download.user-flat-folder: false", "config.template.download.user-flat-folder.comment");
        appendSetting(config, messages, "download.max-concurrent: 10", "config.template.download.max-concurrent.comment");
        appendSetting(config, messages, "download.novel-max-concurrent: 10", "config.template.download.novel-max-concurrent.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.proxy");
        appendSetting(config, messages, "proxy.enabled: true", "config.template.proxy.enabled.comment");
        appendSetting(config, messages, "proxy.host: 127.0.0.1", "config.template.proxy.host.comment");
        appendSetting(config, messages, "proxy.port: 7890", "config.template.proxy.port.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.multi-mode");
        appendSetting(config, messages, "multi-mode.quota.enabled: true", "config.template.multi-mode.quota.enabled.comment");
        appendSetting(config, messages, "multi-mode.quota.max-artworks: 50", "config.template.multi-mode.quota.max-artworks.comment");
        appendSetting(config, messages, "multi-mode.quota.reset-period-hours: 24", "config.template.multi-mode.quota.reset-period-hours.comment");
        appendSetting(config, messages, "multi-mode.quota.archive-expire-minutes: 60", "config.template.multi-mode.quota.archive-expire-minutes.comment");
        appendSetting(config, messages, "multi-mode.quota.limit-image: 0", "config.template.multi-mode.quota.limit-image.comment");
        appendSetting(config, messages, "multi-mode.quota.max-proxy-requests: 200", "config.template.multi-mode.quota.max-proxy-requests.comment");
        appendSetting(config, messages, "multi-mode.quota.archive-max-concurrent: 10", "config.template.multi-mode.quota.archive-max-concurrent.comment");
        appendBlankLine(config);

        appendComment(config, messages.apply("config.template.multi-mode.post-download-mode.comment"));
        appendIndentedComment(config, messages.apply("config.template.multi-mode.post-download-mode.pack-and-delete"));
        appendIndentedComment(config, messages.apply("config.template.multi-mode.post-download-mode.never-delete"));
        appendIndentedComment(config, messages.apply("config.template.multi-mode.post-download-mode.timed-delete"));
        config.append("multi-mode.post-download-mode: pack-and-delete\n\n");

        appendSetting(config, messages, "multi-mode.delete-after-hours: 72", "config.template.multi-mode.delete-after-hours.comment");
        appendBlankLine(config);

        appendSetting(config, messages, "multi-mode.request-limit-minute: 300", "config.template.multi-mode.request-limit-minute.comment");
        appendSetting(config, messages, "multi-mode.static-resource-request-limit-minute: 1200", "config.template.multi-mode.static-resource-request-limit-minute.comment");
        appendSetting(config, messages, "multi-mode.limit-page: 3", "config.template.multi-mode.limit-page.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.guest-invite");
        appendSetting(config, messages, "guest-invite.request-limit-minute: 300", "config.template.guest-invite.request-limit-minute.comment");
        appendSetting(config, messages, "guest-invite.static-resource-request-limit-minute: 1200", "config.template.guest-invite.static-resource-request-limit-minute.comment");
        appendSetting(config, messages, "guest-invite.tts-request-limit-minute: 30", "config.template.guest-invite.tts-request-limit-minute.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.login-security");
        appendSetting(config, messages, "setup.login-rate-limit-minute: 10", "config.template.setup.login-rate-limit-minute.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.maintenance");
        appendSetting(config, messages, "maintenance.enabled: true", "config.template.maintenance.enabled.comment");
        appendSetting(config, messages, "maintenance.monday.enabled: true", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.monday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.tuesday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.tuesday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.wednesday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.wednesday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.thursday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.thursday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.friday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.friday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.saturday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.saturday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.sunday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.sunday.time: \"10:00\"", "config.template.maintenance.day.time.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.ssl");
        appendSetting(config, messages, "ssl.domain: localhost", "config.template.ssl.domain.comment");
        appendSetting(config, messages, "ssl.type: pem", "config.template.ssl.type.comment");
        appendSetting(config, messages, "server.ssl.enabled: false", "config.template.server.ssl.enabled.comment");
        appendSetting(config, messages, "server.ssl.certificate:", "config.template.server.ssl.certificate.comment");
        appendSetting(config, messages, "server.ssl.certificate-private-key:", "config.template.server.ssl.certificate-private-key.comment");
        appendSetting(config, messages, "server.ssl.key-store-type: JKS", "config.template.server.ssl.key-store-type.comment");
        appendSetting(config, messages, "server.ssl.key-store:", "config.template.server.ssl.key-store.comment");
        appendSetting(config, messages, "server.ssl.key-store-password:", "config.template.server.ssl.key-store-password.comment");
        appendSetting(config, messages, "ssl.http-redirect: false", "config.template.ssl.http-redirect.comment");
        appendSetting(config, messages, "ssl.http-redirect-port: 80", "config.template.ssl.http-redirect-port.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.language");
        appendSetting(config, messages, "app.language:", "config.template.app.language.comment");
        appendSetting(config, messages, "app.theme: system", "config.template.app.theme.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.update");
        appendSetting(config, messages, "update.enabled: true", "config.template.update.enabled.comment");
        appendSetting(config, messages,
                "update.manifest-url: " + top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_MANIFEST_URL,
                "config.template.update.manifest-url.comment");
        appendSetting(config, messages,
                "update.nightly-manifest-url: " + top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_NIGHTLY_MANIFEST_URL,
                "config.template.update.nightly-manifest-url.comment");
        appendSetting(config, messages, "update.auto-check: true", "config.template.update.auto-check.comment");
        appendSetting(config, messages,
                "update.check-nightly: " + top.sywyar.pixivdownload.update.UpdateConfig.isCurrentVersionNightly(),
                "config.template.update.check-nightly.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.schedule");
        appendSetting(config, messages, "schedule.enabled: true", "config.template.schedule.enabled.comment");
        appendSetting(config, messages, "schedule.tick-interval-ms: 60000", "config.template.schedule.tick-interval-ms.comment");
        appendSetting(config, messages, "schedule.max-tasks: 100", "config.template.schedule.max-tasks.comment");
        appendSetting(config, messages, "schedule.inbox-check-every: 500", "config.template.schedule.inbox-check-every.comment");
        appendSetting(config, messages, "schedule.auth-failure-circuit-breaker: 5", "config.template.schedule.auth-failure-circuit-breaker.comment");
        appendSetting(config, messages, "schedule.pending-max-attempts: 5", "config.template.schedule.pending-max-attempts.comment");
        appendSetting(config, messages, "schedule.overuse-defer-default-minutes: 60", "config.template.schedule.overuse-defer-default-minutes.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.mail");
        appendSetting(config, messages, "mail.enabled: false", "config.template.mail.enabled.comment");
        appendSetting(config, messages, "mail.host:", "config.template.mail.host.comment");
        appendSetting(config, messages, "mail.port: 587", "config.template.mail.port.comment");
        appendSetting(config, messages, "mail.security: starttls", "config.template.mail.security.comment");
        appendSetting(config, messages, "mail.username:", "config.template.mail.username.comment");
        appendSetting(config, messages, "mail.password:", "config.template.mail.password.comment");
        appendSetting(config, messages, "mail.from:", "config.template.mail.from.comment");
        appendSetting(config, messages, "mail.to:", "config.template.mail.to.comment");
        appendSetting(config, messages, "mail.socks-proxy:", "config.template.mail.socks-proxy.comment");
        appendSetting(config, messages, "mail.subject-prefix: \"[PixivDownloader]\"", "config.template.mail.subject-prefix.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.ai");
        appendSetting(config, messages, "ai.enabled: false", "config.template.ai.enabled.comment");
        appendSetting(config, messages, "ai.base-url:", "config.template.ai.base-url.comment");
        appendSetting(config, messages, "ai.api-key:", "config.template.ai.api-key.comment");
        appendSetting(config, messages, "ai.model:", "config.template.ai.model.comment");
        appendSetting(config, messages, "ai.use-proxy: false", "config.template.ai.use-proxy.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.narration-tts");
        appendSetting(config, messages, "narration-tts.engine: voxcpm", "config.template.narration-tts.engine.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.base-url:", "config.template.narration-tts.voxcpm.base-url.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.api-key:", "config.template.narration-tts.voxcpm.api-key.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.model: openbmb/VoxCPM2", "config.template.narration-tts.voxcpm.model.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.response-format: wav", "config.template.narration-tts.voxcpm.response-format.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.use-proxy: false", "config.template.narration-tts.voxcpm.use-proxy.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.enable-clone: true", "config.template.narration-tts.voxcpm.enable-clone.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.max-new-tokens: 4096", "config.template.narration-tts.voxcpm.max-new-tokens.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.push");
        appendSetting(config, messages, "push.enabled: false", "config.template.push.enabled.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.bark.enabled: false", "config.template.push.bark.enabled.comment");
        appendSetting(config, messages, "push.bark.server: https://api.day.app", "config.template.push.bark.server.comment");
        appendSetting(config, messages, "push.bark.device-key:", "config.template.push.bark.device-key.comment");
        appendSetting(config, messages, "push.bark.sound:", "config.template.push.bark.sound.comment");
        appendSetting(config, messages, "push.bark.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.dingtalk.enabled: false", "config.template.push.dingtalk.enabled.comment");
        appendSetting(config, messages, "push.dingtalk.access-token:", "config.template.push.dingtalk.access-token.comment");
        appendSetting(config, messages, "push.dingtalk.secret:", "config.template.push.dingtalk.secret.comment");
        appendSetting(config, messages, "push.dingtalk.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.telegram.enabled: false", "config.template.push.telegram.enabled.comment");
        appendSetting(config, messages, "push.telegram.bot-token:", "config.template.push.telegram.bot-token.comment");
        appendSetting(config, messages, "push.telegram.chat-id:", "config.template.push.telegram.chat-id.comment");
        appendSetting(config, messages, "push.telegram.use-proxy: true", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.feishu.enabled: false", "config.template.push.feishu.enabled.comment");
        appendSetting(config, messages, "push.feishu.webhook-key:", "config.template.push.feishu.webhook-key.comment");
        appendSetting(config, messages, "push.feishu.secret:", "config.template.push.feishu.secret.comment");
        appendSetting(config, messages, "push.feishu.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.wecom.enabled: false", "config.template.push.wecom.enabled.comment");
        appendSetting(config, messages, "push.wecom.key:", "config.template.push.wecom.key.comment");
        appendSetting(config, messages, "push.wecom.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.pushplus.enabled: false", "config.template.push.pushplus.enabled.comment");
        appendSetting(config, messages, "push.pushplus.token:", "config.template.push.pushplus.token.comment");
        appendSetting(config, messages, "push.pushplus.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.serverchan.enabled: false", "config.template.push.serverchan.enabled.comment");
        appendSetting(config, messages, "push.serverchan.send-key:", "config.template.push.serverchan.send-key.comment");
        appendSetting(config, messages, "push.serverchan.use-proxy: false", "config.template.push.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "push.webhook.enabled: false", "config.template.push.webhook.enabled.comment");
        appendSetting(config, messages, "push.webhook.url:", "config.template.push.webhook.url.comment");
        appendSetting(config, messages, "push.webhook.content-type: application/json", "config.template.push.webhook.content-type.comment");
        appendSetting(config, messages, "push.webhook.body-template:", "config.template.push.webhook.body-template.comment");
        appendSetting(config, messages, "push.webhook.use-proxy: false", "config.template.push.use-proxy.comment");

        return config.toString();
    }

    private static void appendSection(StringBuilder builder, Function<String, String> messages, String titleCode) {
        appendComment(builder, messages.apply(titleCode));
        appendBlankLine(builder);
    }

    private static void appendSetting(StringBuilder builder, Function<String, String> messages,
                                      String keyValue, String commentCode) {
        builder.append(String.format(
                Locale.ROOT,
                "%-" + CONFIG_ENTRY_WIDTH + "s # %s%n",
                keyValue,
                messages.apply(commentCode)
        ));
    }

    private static void appendComment(StringBuilder builder, String text) {
        builder.append(comment(text)).append("\n");
    }

    private static void appendIndentedComment(StringBuilder builder, String text) {
        builder.append(comment("  " + text)).append("\n");
    }

    private static void appendBlankLine(StringBuilder builder) {
        builder.append("\n");
    }
}
