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
        appendSetting(config, messages, "maintenance.monday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.tuesday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.tuesday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.wednesday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.wednesday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.thursday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.thursday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.friday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.friday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.saturday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.saturday.time: 10:00", "config.template.maintenance.day.time.comment");
        appendSetting(config, messages, "maintenance.sunday.enabled: false", "config.template.maintenance.day.enabled.comment");
        appendSetting(config, messages, "maintenance.sunday.time: 10:00", "config.template.maintenance.day.time.comment");
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
