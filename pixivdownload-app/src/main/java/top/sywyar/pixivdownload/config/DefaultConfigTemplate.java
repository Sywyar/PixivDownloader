package top.sywyar.pixivdownload.config;

import top.sywyar.pixivdownload.notification.NotificationConfig;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

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
        appendSetting(config, messages, "download.novel-translate-max-concurrent: 10", "config.template.download.novel-translate-max-concurrent.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.plugins");
        // 内置可禁用功能插件的开关从 BuiltInPlugins 清单动态派生（必选插件如核心 / 下载工作台 / 计划任务宿主不写开关），
        // 与 GUI 配置面板（ConfigFieldRegistry）同源——新增内置功能插件时模板自动跟随、不再漏配。
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            if (plugin.kind() == PluginKind.FEATURE && !plugin.required()) {
                appendSetting(config, messages, "plugins." + plugin.id() + ".enabled: true",
                        "config.template.plugins.enabled.comment");
            }
        }
        // 官方外置 PF4J 插件不在内置清单内，单独写出其开关（缺项默认启用）。
        appendSetting(config, messages, "plugins.stats.enabled: true", "config.template.plugins.enabled.comment");
        appendSetting(config, messages, "plugins.gui-theme.enabled: true", "config.template.plugins.enabled.comment");
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.plugin-catalog");
        appendSetting(config, messages, "plugin-catalog.enabled: false", "config.template.plugin-catalog.enabled.comment");
        appendSetting(config, messages, "plugin-catalog.official-repository-enabled: true", "config.template.plugin-catalog.official-repository-enabled.comment");
        appendSetting(config, messages, "plugin-catalog.connect-timeout-ms: 15000", "config.template.plugin-catalog.connect-timeout-ms.comment");
        appendSetting(config, messages, "plugin-catalog.read-timeout-ms: 60000", "config.template.plugin-catalog.read-timeout-ms.comment");
        appendSetting(config, messages, "plugin-catalog.max-manifest-bytes: 1048576", "config.template.plugin-catalog.max-manifest-bytes.comment");
        appendSetting(config, messages, "plugin-catalog.max-package-bytes: 104857600", "config.template.plugin-catalog.max-package-bytes.comment");
        // 自定义仓库列表：默认空（不访问任何第三方地址）。由 GUI「插件」分组的仓库列表编辑器结构化读写。
        appendSetting(config, messages, "plugin-catalog.repositories:", "config.template.plugin-catalog.repositories.comment");
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
        appendSetting(config, messages, "narration-tts.voxcpm.voice:", "config.template.narration-tts.voxcpm.voice.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.response-format: wav", "config.template.narration-tts.voxcpm.response-format.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.use-proxy: false", "config.template.narration-tts.voxcpm.use-proxy.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.enable-clone: true", "config.template.narration-tts.voxcpm.enable-clone.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.clone-mode: controllable", "config.template.narration-tts.voxcpm.clone-mode.comment");
        appendSetting(config, messages, "narration-tts.voxcpm.max-new-tokens: 4096", "config.template.narration-tts.voxcpm.max-new-tokens.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.mimo.base-url: https://api.xiaomimimo.com/v1", "config.template.narration-tts.mimo.base-url.comment");
        appendSetting(config, messages, "narration-tts.mimo.api-key:", "config.template.narration-tts.mimo.api-key.comment");
        appendSetting(config, messages, "narration-tts.mimo.model: mimo-v2.5-tts", "config.template.narration-tts.mimo.model.comment");
        appendSetting(config, messages, "narration-tts.mimo.voice-design-model: mimo-v2.5-tts-voicedesign", "config.template.narration-tts.mimo.voice-design-model.comment");
        appendSetting(config, messages, "narration-tts.mimo.voice-clone-model: mimo-v2.5-tts-voiceclone", "config.template.narration-tts.mimo.voice-clone-model.comment");
        appendSetting(config, messages, "narration-tts.mimo.voice:", "config.template.narration-tts.mimo.voice.comment");
        appendSetting(config, messages, "narration-tts.mimo.response-format: wav", "config.template.narration-tts.mimo.response-format.comment");
        appendSetting(config, messages, "narration-tts.mimo.use-proxy: false", "config.template.narration-tts.mimo.use-proxy.comment");
        appendSetting(config, messages, "narration-tts.mimo.enable-clone: true", "config.template.narration-tts.mimo.enable-clone.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.cosyvoice.base-url:", "config.template.narration-tts.cosyvoice.base-url.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.api-key:", "config.template.narration-tts.cosyvoice.api-key.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.model: CosyVoice2-0.5B", "config.template.narration-tts.cosyvoice.model.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.voice:", "config.template.narration-tts.cosyvoice.voice.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.response-format: wav", "config.template.narration-tts.cosyvoice.response-format.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.use-proxy: false", "config.template.narration-tts.cosyvoice.use-proxy.comment");
        appendSetting(config, messages, "narration-tts.cosyvoice.enable-clone: true", "config.template.narration-tts.cosyvoice.enable-clone.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.fish.base-url: https://api.fish.audio", "config.template.narration-tts.fish.base-url.comment");
        appendSetting(config, messages, "narration-tts.fish.api-key:", "config.template.narration-tts.fish.api-key.comment");
        appendSetting(config, messages, "narration-tts.fish.model: s1", "config.template.narration-tts.fish.model.comment");
        appendSetting(config, messages, "narration-tts.fish.reference-id:", "config.template.narration-tts.fish.reference-id.comment");
        appendSetting(config, messages, "narration-tts.fish.format: mp3", "config.template.narration-tts.fish.format.comment");
        appendSetting(config, messages, "narration-tts.fish.use-proxy: false", "config.template.narration-tts.fish.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.minimax.base-url: https://api.minimax.io/v1", "config.template.narration-tts.minimax.base-url.comment");
        appendSetting(config, messages, "narration-tts.minimax.api-key:", "config.template.narration-tts.minimax.api-key.comment");
        appendSetting(config, messages, "narration-tts.minimax.group-id:", "config.template.narration-tts.minimax.group-id.comment");
        appendSetting(config, messages, "narration-tts.minimax.model: speech-2.8-hd", "config.template.narration-tts.minimax.model.comment");
        appendSetting(config, messages, "narration-tts.minimax.voice-id:", "config.template.narration-tts.minimax.voice-id.comment");
        appendSetting(config, messages, "narration-tts.minimax.emotion:", "config.template.narration-tts.minimax.emotion.comment");
        appendSetting(config, messages, "narration-tts.minimax.format: mp3", "config.template.narration-tts.minimax.format.comment");
        appendSetting(config, messages, "narration-tts.minimax.sample-rate: 32000", "config.template.narration-tts.minimax.sample-rate.comment");
        appendSetting(config, messages, "narration-tts.minimax.use-proxy: false", "config.template.narration-tts.minimax.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.elevenlabs.base-url: https://api.elevenlabs.io", "config.template.narration-tts.elevenlabs.base-url.comment");
        appendSetting(config, messages, "narration-tts.elevenlabs.api-key:", "config.template.narration-tts.elevenlabs.api-key.comment");
        appendSetting(config, messages, "narration-tts.elevenlabs.model: eleven_v3", "config.template.narration-tts.elevenlabs.model.comment");
        appendSetting(config, messages, "narration-tts.elevenlabs.voice-id:", "config.template.narration-tts.elevenlabs.voice-id.comment");
        appendSetting(config, messages, "narration-tts.elevenlabs.output-format: mp3_44100_128", "config.template.narration-tts.elevenlabs.output-format.comment");
        appendSetting(config, messages, "narration-tts.elevenlabs.use-proxy: false", "config.template.narration-tts.elevenlabs.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.qwen.base-url: https://dashscope.aliyuncs.com/api/v1", "config.template.narration-tts.qwen.base-url.comment");
        appendSetting(config, messages, "narration-tts.qwen.api-key:", "config.template.narration-tts.qwen.api-key.comment");
        appendSetting(config, messages, "narration-tts.qwen.model: qwen3-tts-flash", "config.template.narration-tts.qwen.model.comment");
        appendSetting(config, messages, "narration-tts.qwen.voice: Cherry", "config.template.narration-tts.qwen.voice.comment");
        appendSetting(config, messages, "narration-tts.qwen.language-type:", "config.template.narration-tts.qwen.language-type.comment");
        appendSetting(config, messages, "narration-tts.qwen.use-proxy: false", "config.template.narration-tts.qwen.use-proxy.comment");
        appendBlankLine(config);
        appendSetting(config, messages, "narration-tts.doubao.base-url: https://openspeech.bytedance.com", "config.template.narration-tts.doubao.base-url.comment");
        appendSetting(config, messages, "narration-tts.doubao.app-id:", "config.template.narration-tts.doubao.app-id.comment");
        appendSetting(config, messages, "narration-tts.doubao.access-token:", "config.template.narration-tts.doubao.access-token.comment");
        appendSetting(config, messages, "narration-tts.doubao.cluster: volcano_tts", "config.template.narration-tts.doubao.cluster.comment");
        appendSetting(config, messages, "narration-tts.doubao.voice-type:", "config.template.narration-tts.doubao.voice-type.comment");
        appendSetting(config, messages, "narration-tts.doubao.encoding: mp3", "config.template.narration-tts.doubao.encoding.comment");
        appendSetting(config, messages, "narration-tts.doubao.emotion:", "config.template.narration-tts.doubao.emotion.comment");
        appendSetting(config, messages, "narration-tts.doubao.use-proxy: false", "config.template.narration-tts.doubao.use-proxy.comment");
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
        appendBlankLine(config);

        appendSection(config, messages, "config.template.section.notification");
        for (NotificationScenario scenario : NotificationScenario.values()) {
            appendSetting(config, messages,
                    NotificationConfig.scenarioEnabledKey(scenario.id()) + ": true",
                    "config.template.notification.scenario.enabled.comment");
        }

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
