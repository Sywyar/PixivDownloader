package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.notification.NotificationConfig;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import static top.sywyar.pixivdownload.gui.config.FieldType.*;

/**
 * 所有配置字段的单一事实源。
 * <p>
 * {@link #allFields()} 与 {@link #groups()} 在每次调用时按当前 locale 重新构建，
 * 这样 GUI 切换语言后再次构造 {@code ConfigPanel} 即可拿到本地化后的标签与分组名。
 * 不可改回 {@code static final List}：那样会在类加载时锁定 locale，热重载失效。
 */
public final class ConfigFieldRegistry {

    private ConfigFieldRegistry() {}

    /**
     * 多人模式分组名（按当前 locale）。
     * ConfigPanel 在 solo 模式下据此隐藏整组。
     */
    public static String groupMultiMode() {
        return message("gui.config.group.multi-mode");
    }

    public static String groupMaintenance() {
        return message("gui.config.group.maintenance");
    }

    /** 插件 / 插件市场分组名（按当前 locale）。{@code PluginMarketConfigSection} 据此接管该分组标签页。 */
    public static String groupPlugins() {
        return message("gui.config.group.plugins");
    }

    /** AI 配置分组名（按当前 locale）。 */
    public static String groupAi() {
        return message("gui.config.group.ai");
    }

    /** AI 听小说朗读 TTS 分组名（按当前 locale）。其字段并入 AI 配置页的「TTS 模型」模态卡片，不再单独成页。 */
    public static String groupNarrationTts() {
        return message("gui.config.group.narration-tts");
    }

    /** 通知分组名（邮件 / SMTP + 多通道推送，按当前 locale）。 */
    public static String groupNotification() {
        return message("gui.config.group.notification");
    }

    /** 全部分组名（按当前 locale，保持顺序）。 */
    public static List<String> groups() {
        return List.of(
                message("gui.config.group.server"),
                message("gui.config.group.download"),
                message("gui.config.group.plugins"),
                message("gui.config.group.proxy"),
                message("gui.config.group.multi-mode"),
                message("gui.config.group.guest-invite"),
                message("gui.config.group.security"),
                message("gui.config.group.maintenance"),
                message("gui.config.group.https"),
                message("gui.config.group.update"),
                message("gui.config.group.schedule"),
                message("gui.config.group.ai"),
                message("gui.config.group.notification")
        );
    }

    /** 全部配置字段（按当前 locale 重建标签/帮助文本）。 */
    public static List<ConfigFieldSpec> allFields() {
        String groupServer = message("gui.config.group.server");
        String groupDownload = message("gui.config.group.download");
        String groupPlugins = message("gui.config.group.plugins");
        String groupProxy = message("gui.config.group.proxy");
        String groupMultiMode = message("gui.config.group.multi-mode");
        String groupGuestInvite = message("gui.config.group.guest-invite");
        String groupSecurity = message("gui.config.group.security");
        String groupMaintenance = message("gui.config.group.maintenance");
        String groupHttps = message("gui.config.group.https");
        String groupUpdate = message("gui.config.group.update");
        String groupSchedule = message("gui.config.group.schedule");
        String groupAi = message("gui.config.group.ai");
        String groupNarrationTts = message("gui.config.group.narration-tts");
        String groupNotification = message("gui.config.group.notification");

        List<ConfigFieldSpec> baseFields = List.of(

                // ── 服务器 ─────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("server.port", message("gui.config.field.server.port.label"), PORT, groupServer)
                        .defaultValue("6999")
                        .help(message("gui.config.field.server.port.help"))
                        .validator(v -> {
                            try {
                                int p = Integer.parseInt(v);
                                return (p >= 1 && p <= 65535) ? null : message("gui.config.validation.port-range");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-port");
                            }
                        })
                        .build(),

                // 调试模式：默认隐藏，仅在 GUI 中触发彩蛋后（DebugUnlockState 解锁）才显示。
                ConfigFieldSpec.builder("debug.enabled", message("gui.config.field.debug.enabled.label"), BOOL, groupServer)
                        .defaultValue("false")
                        .help(message("gui.config.field.debug.enabled.help"))
                        .visibleWhen(snap -> top.sywyar.pixivdownload.gui.DebugUnlockState.isUnlocked())
                        .build(),

                // ── 下载 ───────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("download.root-folder", message("gui.config.field.download.root-folder.label"), PATH_DIR, groupDownload)
                        .defaultValue("pixiv-download")
                        .help(message("gui.config.field.download.root-folder.help"))
                        .build(),

                ConfigFieldSpec.builder("download.user-flat-folder", message("gui.config.field.download.user-flat-folder.label"), BOOL, groupDownload)
                        .defaultValue("false")
                        .help(message("gui.config.field.download.user-flat-folder.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("download.max-concurrent", message("gui.config.field.download.max-concurrent.label"), INT, groupDownload)
                        .defaultValue("10")
                        .help(message("gui.config.field.download.max-concurrent.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .build(),

                ConfigFieldSpec.builder("download.novel-max-concurrent", message("gui.config.field.download.novel-max-concurrent.label"), INT, groupDownload)
                        .defaultValue("10")
                        .help(message("gui.config.field.download.novel-max-concurrent.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .build(),

                ConfigFieldSpec.builder("download.novel-translate-max-concurrent", message("gui.config.field.download.novel-translate-max-concurrent.label"), INT, groupDownload)
                        .defaultValue("10")
                        .help(message("gui.config.field.download.novel-translate-max-concurrent.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .build(),

                // 内置插件开关字段在 baseFields 之后由 BuiltInPlugins 清单动态派生（见下），此处不再硬编码。

                // ── 插件市场 / 受信 catalog（归入「插件」分组）──────────────────────
                // 受信 catalog 主开关：默认关闭（开启前不联网）；高级项（超时 / 字节上限 / repositories 列表）
                // 不入字段网格，仅可手写 config.yaml。两项均需重启（仓库注册中心在启动期构建）。
                ConfigFieldSpec.builder("plugin-catalog.enabled", message("gui.config.field.plugin-catalog.enabled.label"), BOOL, groupPlugins)
                        .defaultValue("false")
                        .help(message("gui.config.field.plugin-catalog.enabled.help"))
                        .build(),

                ConfigFieldSpec.builder("plugin-catalog.official-repository-enabled", message("gui.config.field.plugin-catalog.official-repository-enabled.label"), BOOL, groupPlugins)
                        .defaultValue("true")
                        .help(message("gui.config.field.plugin-catalog.official-repository-enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("plugin-catalog.enabled"))
                        .build(),

                // 全局默认（仓库级可覆盖；自定义仓库列表本身由 PluginMarketConfigSection 的表格编辑器管理、不入字段网格）。
                ConfigFieldSpec.builder("plugin-catalog.connect-timeout-ms", message("gui.config.field.plugin-catalog.connect-timeout-ms.label"), INT, groupPlugins)
                        .defaultValue("15000")
                        .help(message("gui.config.field.plugin-catalog.connect-timeout-ms.help"))
                        .validator(ConfigFieldRegistry::validatePositiveInt)
                        .build(),

                ConfigFieldSpec.builder("plugin-catalog.read-timeout-ms", message("gui.config.field.plugin-catalog.read-timeout-ms.label"), INT, groupPlugins)
                        .defaultValue("60000")
                        .help(message("gui.config.field.plugin-catalog.read-timeout-ms.help"))
                        .validator(ConfigFieldRegistry::validatePositiveInt)
                        .build(),

                ConfigFieldSpec.builder("plugin-catalog.max-manifest-bytes", message("gui.config.field.plugin-catalog.max-manifest-bytes.label"), INT, groupPlugins)
                        .defaultValue("1048576")
                        .help(message("gui.config.field.plugin-catalog.max-manifest-bytes.help"))
                        .validator(ConfigFieldRegistry::validatePositiveInt)
                        .build(),

                ConfigFieldSpec.builder("plugin-catalog.max-package-bytes", message("gui.config.field.plugin-catalog.max-package-bytes.label"), INT, groupPlugins)
                        .defaultValue("104857600")
                        .help(message("gui.config.field.plugin-catalog.max-package-bytes.help"))
                        .validator(ConfigFieldRegistry::validatePositiveInt)
                        .build(),

                // ── 代理 ───────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("proxy.enabled", message("gui.config.field.proxy.enabled.label"), BOOL, groupProxy)
                        .defaultValue("true")
                        .help(message("gui.config.field.proxy.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("proxy.host", message("gui.config.field.proxy.host.label"), STRING, groupProxy)
                        .defaultValue("127.0.0.1")
                        .help(message("gui.config.field.proxy.host.help"))
                        .enabledWhen(snap -> snap.isTrue("proxy.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("proxy.port", message("gui.config.field.proxy.port.label"), PORT, groupProxy)
                        .defaultValue("7890")
                        .help(message("gui.config.field.proxy.port.help"))
                        .enabledWhen(snap -> snap.isTrue("proxy.enabled"))
                        .validator(v -> {
                            try {
                                int p = Integer.parseInt(v);
                                return (p >= 1 && p <= 65535) ? null : message("gui.config.validation.port-range");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-port");
                            }
                        })
                        .hotReloadable()
                        .build(),

                // ── 多人模式 ────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("multi-mode.quota.enabled", message("gui.config.field.multi-mode.quota.enabled.label"), BOOL, groupMultiMode)
                        .defaultValue("true")
                        .help(message("gui.config.field.multi-mode.quota.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.max-artworks", message("gui.config.field.multi-mode.quota.max-artworks.label"), INT, groupMultiMode)
                        .defaultValue("50")
                        .help(message("gui.config.field.multi-mode.quota.max-artworks.help"))
                        .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.reset-period-hours", message("gui.config.field.multi-mode.quota.reset-period-hours.label"), INT, groupMultiMode)
                        .defaultValue("24")
                        .help(message("gui.config.field.multi-mode.quota.reset-period-hours.help"))
                        .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.archive-expire-minutes", message("gui.config.field.multi-mode.quota.archive-expire-minutes.label"), INT, groupMultiMode)
                        .defaultValue("60")
                        .help(message("gui.config.field.multi-mode.quota.archive-expire-minutes.help"))
                        .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.limit-image", message("gui.config.field.multi-mode.quota.limit-image.label"), INT, groupMultiMode)
                        .defaultValue("0")
                        .help(message("gui.config.field.multi-mode.quota.limit-image.help"))
                        .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.max-proxy-requests", message("gui.config.field.multi-mode.quota.max-proxy-requests.label"), INT, groupMultiMode)
                        .defaultValue("200")
                        .help(message("gui.config.field.multi-mode.quota.max-proxy-requests.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.quota.archive-max-concurrent", message("gui.config.field.multi-mode.quota.archive-max-concurrent.label"), INT, groupMultiMode)
                        .defaultValue("10")
                        .help(message("gui.config.field.multi-mode.quota.archive-max-concurrent.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .build(),

                ConfigFieldSpec.builder("multi-mode.post-download-mode", message("gui.config.field.multi-mode.post-download-mode.label"), ENUM, groupMultiMode)
                        .defaultValue("pack-and-delete")
                        .enumValues("pack-and-delete", "never-delete", "timed-delete")
                        .help(message("gui.config.field.multi-mode.post-download-mode.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.delete-after-hours", message("gui.config.field.multi-mode.delete-after-hours.label"), INT, groupMultiMode)
                        .defaultValue("72")
                        .help(message("gui.config.field.multi-mode.delete-after-hours.help"))
                        .enabledWhen(snap -> snap.equals("multi-mode.post-download-mode", "timed-delete"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.request-limit-minute", message("gui.config.field.multi-mode.request-limit-minute.label"), INT, groupMultiMode)
                        .defaultValue("300")
                        .help(message("gui.config.field.multi-mode.request-limit-minute.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.static-resource-request-limit-minute", message("gui.config.field.multi-mode.static-resource-request-limit-minute.label"), INT, groupMultiMode)
                        .defaultValue("1200")
                        .help(message("gui.config.field.multi-mode.static-resource-request-limit-minute.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("multi-mode.limit-page", message("gui.config.field.multi-mode.limit-page.label"), INT, groupMultiMode)
                        .defaultValue("3")
                        .help(message("gui.config.field.multi-mode.limit-page.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                // ── 访客邀请限流（solo / multi 均生效）──────────────────────────────
                ConfigFieldSpec.builder("guest-invite.request-limit-minute", message("gui.config.field.guest-invite.request-limit-minute.label"), INT, groupGuestInvite)
                        .defaultValue("300")
                        .help(message("gui.config.field.guest-invite.request-limit-minute.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("guest-invite.static-resource-request-limit-minute", message("gui.config.field.guest-invite.static-resource-request-limit-minute.label"), INT, groupGuestInvite)
                        .defaultValue("1200")
                        .help(message("gui.config.field.guest-invite.static-resource-request-limit-minute.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("guest-invite.tts-request-limit-minute", message("gui.config.field.guest-invite.tts-request-limit-minute.label"), INT, groupGuestInvite)
                        .defaultValue("30")
                        .help(message("gui.config.field.guest-invite.tts-request-limit-minute.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 0 ? null : message("gui.config.validation.non-negative-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                // ── 安全 ───────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("setup.login-rate-limit-minute", message("gui.config.field.setup.login-rate-limit-minute.label"), INT, groupSecurity)
                        .defaultValue("10")
                        .help(message("gui.config.field.setup.login-rate-limit-minute.help"))
                        .hotReloadable()
                        .build(),

                // ── 维护 ───────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("maintenance.enabled", message("gui.config.field.maintenance.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("true")
                        .help(message("gui.config.field.maintenance.enabled.help"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.monday.enabled", message("gui.config.field.maintenance.monday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("true")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.monday.time", message("gui.config.field.maintenance.monday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.monday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.monday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.tuesday.enabled", message("gui.config.field.maintenance.tuesday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.tuesday.time", message("gui.config.field.maintenance.tuesday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.tuesday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.tuesday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.wednesday.enabled", message("gui.config.field.maintenance.wednesday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.wednesday.time", message("gui.config.field.maintenance.wednesday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.wednesday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.wednesday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.thursday.enabled", message("gui.config.field.maintenance.thursday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.thursday.time", message("gui.config.field.maintenance.thursday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.thursday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.thursday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.friday.enabled", message("gui.config.field.maintenance.friday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.friday.time", message("gui.config.field.maintenance.friday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.friday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.friday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.saturday.enabled", message("gui.config.field.maintenance.saturday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.saturday.time", message("gui.config.field.maintenance.saturday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.saturday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.saturday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.sunday.enabled", message("gui.config.field.maintenance.sunday.enabled.label"), BOOL, groupMaintenance)
                        .defaultValue("false")
                        .help(message("gui.config.field.maintenance.day.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("maintenance.sunday.time", message("gui.config.field.maintenance.sunday.time.label"), STRING, groupMaintenance)
                        .defaultValue(MaintenanceProperties.DEFAULT_TIME)
                        .help(message("gui.config.field.maintenance.day.time.help"))
                        .visibleWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.sunday.enabled"))
                        .enabledWhen(snap -> snap.isTrue("maintenance.enabled") && snap.isTrue("maintenance.sunday.enabled"))
                        .validator(ConfigFieldRegistry::validateMaintenanceTime)
                        .hotReloadable()
                        .build(),

                // ── HTTPS ──────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("ssl.domain", message("gui.config.field.ssl.domain.label"), STRING, groupHttps)
                        .defaultValue("localhost")
                        .help(message("gui.config.field.ssl.domain.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("server.ssl.enabled", message("gui.config.field.server.ssl.enabled.label"), BOOL, groupHttps)
                        .defaultValue("false")
                        .help(message("gui.config.field.server.ssl.enabled.help"))
                        .build(),

                ConfigFieldSpec.builder("ssl.type", message("gui.config.field.ssl.type.label"), ENUM, groupHttps)
                        .defaultValue("pem")
                        .enumValues("pem", "jks")
                        .help(message("gui.config.field.ssl.type.help"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled"))
                        .build(),

                // PEM 字段：仅当 ssl.type=pem 时显示
                ConfigFieldSpec.builder("server.ssl.certificate", message("gui.config.field.server.ssl.certificate.label"), PATH_FILE, groupHttps)
                        .defaultValue("")
                        .help(message("gui.config.field.server.ssl.certificate.help"))
                        .visibleWhen(snap -> snap.equals("ssl.type", "pem"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.equals("ssl.type", "pem"))
                        .build(),

                ConfigFieldSpec.builder("server.ssl.certificate-private-key", message("gui.config.field.server.ssl.certificate-private-key.label"), PATH_FILE, groupHttps)
                        .defaultValue("")
                        .help(message("gui.config.field.server.ssl.certificate-private-key.help"))
                        .visibleWhen(snap -> snap.equals("ssl.type", "pem"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.equals("ssl.type", "pem"))
                        .build(),

                // JKS 字段：仅当 ssl.type=jks 时显示
                ConfigFieldSpec.builder("server.ssl.key-store-type", message("gui.config.field.server.ssl.key-store-type.label"), ENUM, groupHttps)
                        .defaultValue("JKS")
                        .enumValues("JKS", "PKCS12")
                        .help(message("gui.config.field.server.ssl.key-store-type.help"))
                        .visibleWhen(snap -> snap.equals("ssl.type", "jks"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.equals("ssl.type", "jks"))
                        .build(),

                ConfigFieldSpec.builder("server.ssl.key-store", message("gui.config.field.server.ssl.key-store.label"), PATH_FILE, groupHttps)
                        .defaultValue("")
                        .help(message("gui.config.field.server.ssl.key-store.help"))
                        .visibleWhen(snap -> snap.equals("ssl.type", "jks"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.equals("ssl.type", "jks"))
                        .build(),

                ConfigFieldSpec.builder("server.ssl.key-store-password", message("gui.config.field.server.ssl.key-store-password.label"), PASSWORD, groupHttps)
                        .defaultValue("")
                        .help(message("gui.config.field.server.ssl.key-store-password.help"))
                        .visibleWhen(snap -> snap.equals("ssl.type", "jks"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.equals("ssl.type", "jks"))
                        .build(),

                ConfigFieldSpec.builder("ssl.http-redirect", message("gui.config.field.ssl.http-redirect.label"), BOOL, groupHttps)
                        .defaultValue("false")
                        .help(message("gui.config.field.ssl.http-redirect.help"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled"))
                        .build(),

                ConfigFieldSpec.builder("ssl.http-redirect-port", message("gui.config.field.ssl.http-redirect-port.label"), PORT, groupHttps)
                        .defaultValue("80")
                        .help(message("gui.config.field.ssl.http-redirect-port.help"))
                        .enabledWhen(snap -> snap.isTrue("server.ssl.enabled") && snap.isTrue("ssl.http-redirect"))
                        .build(),

                // ── 在线更新 ────────────────────────────────────────────────────────
                ConfigFieldSpec.builder("update.enabled", message("gui.config.field.update.enabled.label"), BOOL, groupUpdate)
                        .defaultValue("true")
                        .help(message("gui.config.field.update.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("update.manifest-url", message("gui.config.field.update.manifest-url.label"), STRING, groupUpdate)
                        .defaultValue(UpdateConfig.DEFAULT_MANIFEST_URL)
                        .help(message("gui.config.field.update.manifest-url.help"))
                        .enabledWhen(snap -> snap.isTrue("update.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("update.nightly-manifest-url", message("gui.config.field.update.nightly-manifest-url.label"), STRING, groupUpdate)
                        .defaultValue(UpdateConfig.DEFAULT_NIGHTLY_MANIFEST_URL)
                        .help(message("gui.config.field.update.nightly-manifest-url.help"))
                        .enabledWhen(snap -> snap.isTrue("update.enabled") && snap.isTrue("update.check-nightly"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("update.auto-check", message("gui.config.field.update.auto-check.label"), BOOL, groupUpdate)
                        .defaultValue("true")
                        .help(message("gui.config.field.update.auto-check.help"))
                        .enabledWhen(snap -> snap.isTrue("update.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("update.check-nightly", message("gui.config.field.update.check-nightly.label"), BOOL, groupUpdate)
                        .defaultValue(Boolean.toString(UpdateConfig.isCurrentVersionNightly()))
                        .help(message("gui.config.field.update.check-nightly.help"))
                        .enabledWhen(snap -> snap.isTrue("update.enabled"))
                        .hotReloadable()
                        .build(),

                // ── 计划任务（管理员） ──────────────────────────────────────────────
                ConfigFieldSpec.builder("schedule.enabled", message("gui.config.field.schedule.enabled.label"), BOOL, groupSchedule)
                        .defaultValue("true")
                        .help(message("gui.config.field.schedule.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("schedule.tick-interval-ms", message("gui.config.field.schedule.tick-interval-ms.label"), INT, groupSchedule)
                        .defaultValue("60000")
                        .help(message("gui.config.field.schedule.tick-interval-ms.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1000 ? null : message("gui.config.validation.schedule-tick-min");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .build(),

                ConfigFieldSpec.builder("schedule.max-tasks", message("gui.config.field.schedule.max-tasks.label"), INT, groupSchedule)
                        .defaultValue("100")
                        .help(message("gui.config.field.schedule.max-tasks.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("schedule.inbox-check-every", message("gui.config.field.schedule.inbox-check-every.label"), INT, groupSchedule)
                        .defaultValue("500")
                        .help(message("gui.config.field.schedule.inbox-check-every.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("schedule.auth-failure-circuit-breaker", message("gui.config.field.schedule.auth-failure-circuit-breaker.label"), INT, groupSchedule)
                        .defaultValue("5")
                        .help(message("gui.config.field.schedule.auth-failure-circuit-breaker.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("schedule.pending-max-attempts", message("gui.config.field.schedule.pending-max-attempts.label"), INT, groupSchedule)
                        .defaultValue("5")
                        .help(message("gui.config.field.schedule.pending-max-attempts.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 1 ? null : message("gui.config.validation.positive-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("schedule.overuse-defer-default-minutes", message("gui.config.field.schedule.overuse-defer-default-minutes.label"), INT, groupSchedule)
                        .defaultValue("60")
                        .help(message("gui.config.field.schedule.overuse-defer-default-minutes.help"))
                        .enabledWhen(snap -> snap.isTrue("schedule.enabled"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 60 ? null : message("gui.config.validation.schedule-defer-min");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                // ── 邮件 / SMTP ─────────────────────────────────────────────────────
                ConfigFieldSpec.builder("mail.enabled", message("gui.config.field.mail.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.mail.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.host", message("gui.config.field.mail.host.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.host.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.port", message("gui.config.field.mail.port.label"), PORT, groupNotification)
                        .defaultValue("587")
                        .help(message("gui.config.field.mail.port.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .validator(v -> {
                            try {
                                int p = Integer.parseInt(v);
                                return (p >= 1 && p <= 65535) ? null : message("gui.config.validation.port-range");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-port");
                            }
                        })
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.security", message("gui.config.field.mail.security.label"), ENUM, groupNotification)
                        .defaultValue("starttls")
                        .enumValues("none", "ssl", "starttls")
                        .help(message("gui.config.field.mail.security.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.username", message("gui.config.field.mail.username.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.username.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.password", message("gui.config.field.mail.password.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.password.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.from", message("gui.config.field.mail.from.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.from.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.to", message("gui.config.field.mail.to.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.to.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.socks-proxy", message("gui.config.field.mail.socks-proxy.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.mail.socks-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("mail.subject-prefix", message("gui.config.field.mail.subject-prefix.label"), STRING, groupNotification)
                        .defaultValue("[PixivDownloader]")
                        .help(message("gui.config.field.mail.subject-prefix.help"))
                        .enabledWhen(snap -> snap.isTrue("mail.enabled"))
                        .hotReloadable()
                        .build(),

                // ── AI / 大语言模型 ─────────────────────────────────────────────────
                ConfigFieldSpec.builder("ai.enabled", message("gui.config.field.ai.enabled.label"), BOOL, groupAi)
                        .defaultValue("false")
                        .help(message("gui.config.field.ai.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("ai.base-url", message("gui.config.field.ai.base-url.label"), STRING, groupAi)
                        .defaultValue("")
                        .help(message("gui.config.field.ai.base-url.help"))
                        .enabledWhen(snap -> snap.isTrue("ai.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("ai.api-key", message("gui.config.field.ai.api-key.label"), PASSWORD, groupAi)
                        .defaultValue("")
                        .help(message("gui.config.field.ai.api-key.help"))
                        .enabledWhen(snap -> snap.isTrue("ai.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("ai.model", message("gui.config.field.ai.model.label"), STRING, groupAi)
                        .defaultValue("")
                        .help(message("gui.config.field.ai.model.help"))
                        .enabledWhen(snap -> snap.isTrue("ai.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("ai.use-proxy", message("gui.config.field.ai.use-proxy.label"), BOOL, groupAi)
                        .defaultValue("false")
                        .help(message("gui.config.field.ai.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("ai.enabled"))
                        .hotReloadable()
                        .build(),

                // ── AI 听小说朗读 TTS 引擎 ──────────────────────────────────────────
                ConfigFieldSpec.builder("narration-tts.engine", message("gui.config.field.narration-tts.engine.label"), ENUM, groupNarrationTts)
                        .defaultValue("voxcpm")
                        .enumValues("voxcpm", "mimo", "cosyvoice", "fish", "minimax", "elevenlabs", "qwen", "doubao")
                        .help(message("gui.config.field.narration-tts.engine.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.base-url", message("gui.config.field.narration-tts.voxcpm.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.voxcpm.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.api-key", message("gui.config.field.narration-tts.voxcpm.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.voxcpm.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.model", message("gui.config.field.narration-tts.voxcpm.model.label"), STRING, groupNarrationTts)
                        .defaultValue("openbmb/VoxCPM2")
                        .help(message("gui.config.field.narration-tts.voxcpm.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.voice", message("gui.config.field.narration-tts.voxcpm.voice.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.voxcpm.voice.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.response-format", message("gui.config.field.narration-tts.voxcpm.response-format.label"), ENUM, groupNarrationTts)
                        .defaultValue("wav")
                        .enumValues("wav", "pcm")
                        .help(message("gui.config.field.narration-tts.voxcpm.response-format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.use-proxy", message("gui.config.field.narration-tts.voxcpm.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.voxcpm.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.enable-clone", message("gui.config.field.narration-tts.voxcpm.enable-clone.label"), BOOL, groupNarrationTts)
                        .defaultValue("true")
                        .help(message("gui.config.field.narration-tts.voxcpm.enable-clone.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.clone-mode", message("gui.config.field.narration-tts.voxcpm.clone-mode.label"), ENUM, groupNarrationTts)
                        .defaultValue("controllable")
                        .enumValues("controllable", "hifi")
                        .help(message("gui.config.field.narration-tts.voxcpm.clone-mode.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm") && snap.isTrue("narration-tts.voxcpm.enable-clone"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("narration-tts.voxcpm.max-new-tokens", message("gui.config.field.narration-tts.voxcpm.max-new-tokens.label"), INT, groupNarrationTts)
                        .defaultValue("4096")
                        .help(message("gui.config.field.narration-tts.voxcpm.max-new-tokens.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "voxcpm"))
                        .validator(v -> {
                            try {
                                return Integer.parseInt(v) >= 0 ? null : message("gui.config.validation.valid-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),

                // ── MiMo（小米，云 API）──────────────────────────────────────────
                ConfigFieldSpec.builder("narration-tts.mimo.base-url", message("gui.config.field.narration-tts.mimo.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://api.xiaomimimo.com/v1")
                        .help(message("gui.config.field.narration-tts.mimo.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.api-key", message("gui.config.field.narration-tts.mimo.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.mimo.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.model", message("gui.config.field.narration-tts.mimo.model.label"), STRING, groupNarrationTts)
                        .defaultValue("mimo-v2.5-tts")
                        .help(message("gui.config.field.narration-tts.mimo.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.voice-design-model", message("gui.config.field.narration-tts.mimo.voice-design-model.label"), STRING, groupNarrationTts)
                        .defaultValue("mimo-v2.5-tts-voicedesign")
                        .help(message("gui.config.field.narration-tts.mimo.voice-design-model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.voice-clone-model", message("gui.config.field.narration-tts.mimo.voice-clone-model.label"), STRING, groupNarrationTts)
                        .defaultValue("mimo-v2.5-tts-voiceclone")
                        .help(message("gui.config.field.narration-tts.mimo.voice-clone-model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.voice", message("gui.config.field.narration-tts.mimo.voice.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.mimo.voice.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.response-format", message("gui.config.field.narration-tts.mimo.response-format.label"), ENUM, groupNarrationTts)
                        .defaultValue("wav")
                        .enumValues("wav", "pcm16")
                        .help(message("gui.config.field.narration-tts.mimo.response-format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.use-proxy", message("gui.config.field.narration-tts.mimo.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.mimo.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.mimo.enable-clone", message("gui.config.field.narration-tts.mimo.enable-clone.label"), BOOL, groupNarrationTts)
                        .defaultValue("true")
                        .help(message("gui.config.field.narration-tts.mimo.enable-clone.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "mimo"))
                        .hotReloadable()
                        .build(),

                // ── CosyVoice（自建，OpenAI 兼容 /audio/speech）────────────────────
                ConfigFieldSpec.builder("narration-tts.cosyvoice.base-url", message("gui.config.field.narration-tts.cosyvoice.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.cosyvoice.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.api-key", message("gui.config.field.narration-tts.cosyvoice.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.cosyvoice.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.model", message("gui.config.field.narration-tts.cosyvoice.model.label"), STRING, groupNarrationTts)
                        .defaultValue("CosyVoice2-0.5B")
                        .help(message("gui.config.field.narration-tts.cosyvoice.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.voice", message("gui.config.field.narration-tts.cosyvoice.voice.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.cosyvoice.voice.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.response-format", message("gui.config.field.narration-tts.cosyvoice.response-format.label"), ENUM, groupNarrationTts)
                        .defaultValue("wav")
                        .enumValues("wav", "mp3", "pcm")
                        .help(message("gui.config.field.narration-tts.cosyvoice.response-format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.use-proxy", message("gui.config.field.narration-tts.cosyvoice.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.cosyvoice.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.cosyvoice.enable-clone", message("gui.config.field.narration-tts.cosyvoice.enable-clone.label"), BOOL, groupNarrationTts)
                        .defaultValue("true")
                        .help(message("gui.config.field.narration-tts.cosyvoice.enable-clone.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "cosyvoice"))
                        .hotReloadable()
                        .build(),

                // ── Fish Audio（云 API）────────────────────────────────────────────
                ConfigFieldSpec.builder("narration-tts.fish.base-url", message("gui.config.field.narration-tts.fish.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://api.fish.audio")
                        .help(message("gui.config.field.narration-tts.fish.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.fish.api-key", message("gui.config.field.narration-tts.fish.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.fish.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.fish.model", message("gui.config.field.narration-tts.fish.model.label"), STRING, groupNarrationTts)
                        .defaultValue("s1")
                        .help(message("gui.config.field.narration-tts.fish.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.fish.reference-id", message("gui.config.field.narration-tts.fish.reference-id.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.fish.reference-id.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.fish.format", message("gui.config.field.narration-tts.fish.format.label"), ENUM, groupNarrationTts)
                        .defaultValue("mp3")
                        .enumValues("mp3", "wav", "pcm", "opus")
                        .help(message("gui.config.field.narration-tts.fish.format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.fish.use-proxy", message("gui.config.field.narration-tts.fish.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.fish.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "fish"))
                        .hotReloadable()
                        .build(),

                // ── MiniMax（云 API）───────────────────────────────────────────────
                ConfigFieldSpec.builder("narration-tts.minimax.base-url", message("gui.config.field.narration-tts.minimax.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://api.minimax.io/v1")
                        .help(message("gui.config.field.narration-tts.minimax.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.api-key", message("gui.config.field.narration-tts.minimax.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.minimax.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.group-id", message("gui.config.field.narration-tts.minimax.group-id.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.minimax.group-id.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.model", message("gui.config.field.narration-tts.minimax.model.label"), STRING, groupNarrationTts)
                        .defaultValue("speech-2.8-hd")
                        .help(message("gui.config.field.narration-tts.minimax.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.voice-id", message("gui.config.field.narration-tts.minimax.voice-id.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.minimax.voice-id.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.emotion", message("gui.config.field.narration-tts.minimax.emotion.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.minimax.emotion.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.format", message("gui.config.field.narration-tts.minimax.format.label"), ENUM, groupNarrationTts)
                        .defaultValue("mp3")
                        .enumValues("mp3", "wav", "pcm", "flac")
                        .help(message("gui.config.field.narration-tts.minimax.format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.sample-rate", message("gui.config.field.narration-tts.minimax.sample-rate.label"), INT, groupNarrationTts)
                        .defaultValue("32000")
                        .help(message("gui.config.field.narration-tts.minimax.sample-rate.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .validator(v -> {
                            try {
                                return Integer.parseInt(v) > 0 ? null : message("gui.config.validation.valid-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
                            }
                        })
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.minimax.use-proxy", message("gui.config.field.narration-tts.minimax.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.minimax.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "minimax"))
                        .hotReloadable()
                        .build(),

                // ── ElevenLabs（云 API）────────────────────────────────────────────
                ConfigFieldSpec.builder("narration-tts.elevenlabs.base-url", message("gui.config.field.narration-tts.elevenlabs.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://api.elevenlabs.io")
                        .help(message("gui.config.field.narration-tts.elevenlabs.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.elevenlabs.api-key", message("gui.config.field.narration-tts.elevenlabs.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.elevenlabs.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.elevenlabs.model", message("gui.config.field.narration-tts.elevenlabs.model.label"), STRING, groupNarrationTts)
                        .defaultValue("eleven_v3")
                        .help(message("gui.config.field.narration-tts.elevenlabs.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.elevenlabs.voice-id", message("gui.config.field.narration-tts.elevenlabs.voice-id.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.elevenlabs.voice-id.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.elevenlabs.output-format", message("gui.config.field.narration-tts.elevenlabs.output-format.label"), ENUM, groupNarrationTts)
                        .defaultValue("mp3_44100_128")
                        .enumValues("mp3_44100_128", "mp3_44100_64", "pcm_16000", "pcm_24000", "opus_48000_128", "ulaw_8000")
                        .help(message("gui.config.field.narration-tts.elevenlabs.output-format.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.elevenlabs.use-proxy", message("gui.config.field.narration-tts.elevenlabs.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.elevenlabs.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "elevenlabs"))
                        .hotReloadable()
                        .build(),

                // ── Qwen3-TTS（阿里 DashScope / 百炼，云 API）──────────────────────
                ConfigFieldSpec.builder("narration-tts.qwen.base-url", message("gui.config.field.narration-tts.qwen.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://dashscope.aliyuncs.com/api/v1")
                        .help(message("gui.config.field.narration-tts.qwen.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.qwen.api-key", message("gui.config.field.narration-tts.qwen.api-key.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.qwen.api-key.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.qwen.model", message("gui.config.field.narration-tts.qwen.model.label"), STRING, groupNarrationTts)
                        .defaultValue("qwen3-tts-flash")
                        .help(message("gui.config.field.narration-tts.qwen.model.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.qwen.voice", message("gui.config.field.narration-tts.qwen.voice.label"), STRING, groupNarrationTts)
                        .defaultValue("Cherry")
                        .help(message("gui.config.field.narration-tts.qwen.voice.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.qwen.language-type", message("gui.config.field.narration-tts.qwen.language-type.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.qwen.language-type.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.qwen.use-proxy", message("gui.config.field.narration-tts.qwen.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.qwen.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "qwen"))
                        .hotReloadable()
                        .build(),

                // ── 豆包 / Seed-TTS（字节火山引擎，云 API）─────────────────────────
                ConfigFieldSpec.builder("narration-tts.doubao.base-url", message("gui.config.field.narration-tts.doubao.base-url.label"), STRING, groupNarrationTts)
                        .defaultValue("https://openspeech.bytedance.com")
                        .help(message("gui.config.field.narration-tts.doubao.base-url.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.app-id", message("gui.config.field.narration-tts.doubao.app-id.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.doubao.app-id.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.access-token", message("gui.config.field.narration-tts.doubao.access-token.label"), PASSWORD, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.doubao.access-token.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.cluster", message("gui.config.field.narration-tts.doubao.cluster.label"), STRING, groupNarrationTts)
                        .defaultValue("volcano_tts")
                        .help(message("gui.config.field.narration-tts.doubao.cluster.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.voice-type", message("gui.config.field.narration-tts.doubao.voice-type.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.doubao.voice-type.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.encoding", message("gui.config.field.narration-tts.doubao.encoding.label"), ENUM, groupNarrationTts)
                        .defaultValue("mp3")
                        .enumValues("mp3", "wav", "pcm", "ogg_opus")
                        .help(message("gui.config.field.narration-tts.doubao.encoding.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.emotion", message("gui.config.field.narration-tts.doubao.emotion.label"), STRING, groupNarrationTts)
                        .defaultValue("")
                        .help(message("gui.config.field.narration-tts.doubao.emotion.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("narration-tts.doubao.use-proxy", message("gui.config.field.narration-tts.doubao.use-proxy.label"), BOOL, groupNarrationTts)
                        .defaultValue("false")
                        .help(message("gui.config.field.narration-tts.doubao.use-proxy.help"))
                        .enabledWhen(snap -> snap.equals("narration-tts.engine", "doubao"))
                        .hotReloadable()
                        .build(),

                // ── 推送通知（多通道） ──────────────────────────────────────────────
                ConfigFieldSpec.builder("push.enabled", message("gui.config.field.push.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.enabled.help"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.bark.enabled", message("gui.config.field.push.bark.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.bark.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.bark.server", message("gui.config.field.push.bark.server.label"), STRING, groupNotification)
                        .defaultValue("https://api.day.app")
                        .help(message("gui.config.field.push.bark.server.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.bark.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.bark.device-key", message("gui.config.field.push.bark.device-key.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.bark.device-key.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.bark.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.bark.sound", message("gui.config.field.push.bark.sound.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.bark.sound.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.bark.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.bark.use-proxy", message("gui.config.field.push.bark.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.bark.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.dingtalk.enabled", message("gui.config.field.push.dingtalk.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.dingtalk.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.dingtalk.access-token", message("gui.config.field.push.dingtalk.access-token.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.dingtalk.access-token.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.dingtalk.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.dingtalk.secret", message("gui.config.field.push.dingtalk.secret.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.dingtalk.secret.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.dingtalk.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.dingtalk.use-proxy", message("gui.config.field.push.dingtalk.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.dingtalk.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.telegram.enabled", message("gui.config.field.push.telegram.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.telegram.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.telegram.bot-token", message("gui.config.field.push.telegram.bot-token.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.telegram.bot-token.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.telegram.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.telegram.chat-id", message("gui.config.field.push.telegram.chat-id.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.telegram.chat-id.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.telegram.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.telegram.use-proxy", message("gui.config.field.push.telegram.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("true")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.telegram.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.feishu.enabled", message("gui.config.field.push.feishu.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.feishu.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.feishu.webhook-key", message("gui.config.field.push.feishu.webhook-key.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.feishu.webhook-key.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.feishu.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.feishu.secret", message("gui.config.field.push.feishu.secret.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.feishu.secret.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.feishu.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.feishu.use-proxy", message("gui.config.field.push.feishu.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.feishu.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.wecom.enabled", message("gui.config.field.push.wecom.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.wecom.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.wecom.key", message("gui.config.field.push.wecom.key.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.wecom.key.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.wecom.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.wecom.use-proxy", message("gui.config.field.push.wecom.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.wecom.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.pushplus.enabled", message("gui.config.field.push.pushplus.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.pushplus.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.pushplus.token", message("gui.config.field.push.pushplus.token.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.pushplus.token.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.pushplus.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.pushplus.use-proxy", message("gui.config.field.push.pushplus.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.pushplus.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.serverchan.enabled", message("gui.config.field.push.serverchan.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.serverchan.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.serverchan.send-key", message("gui.config.field.push.serverchan.send-key.label"), PASSWORD, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.serverchan.send-key.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.serverchan.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.serverchan.use-proxy", message("gui.config.field.push.serverchan.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.serverchan.enabled"))
                        .hotReloadable()
                        .build(),

                ConfigFieldSpec.builder("push.webhook.enabled", message("gui.config.field.push.webhook.enabled.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.webhook.enabled.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.webhook.url", message("gui.config.field.push.webhook.url.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.webhook.url.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.webhook.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.webhook.content-type", message("gui.config.field.push.webhook.content-type.label"), STRING, groupNotification)
                        .defaultValue("application/json")
                        .help(message("gui.config.field.push.webhook.content-type.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.webhook.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.webhook.body-template", message("gui.config.field.push.webhook.body-template.label"), STRING, groupNotification)
                        .defaultValue("")
                        .help(message("gui.config.field.push.webhook.body-template.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.webhook.enabled"))
                        .hotReloadable()
                        .build(),
                ConfigFieldSpec.builder("push.webhook.use-proxy", message("gui.config.field.push.webhook.use-proxy.label"), BOOL, groupNotification)
                        .defaultValue("false")
                        .help(message("gui.config.field.push.use-proxy.help"))
                        .enabledWhen(snap -> snap.isTrue("push.enabled") && snap.isTrue("push.webhook.enabled"))
                        .hotReloadable()
                        .build()
        );

        List<ConfigFieldSpec> fields = new ArrayList<>(baseFields);

        // 内置功能插件开关：每个<b>可禁用</b>的功能插件一项（默认启用、需重启）。名称 / 简介经插件声明的
        // i18n key（displayName() / description()）在<b>插件自有 namespace</b>（i18n() 贡献的 bundle）中解析，
        // 文案归插件所有、不落在核心 GUI bundle——与导航 nav.label 同一套「插件自有 i18n」机制。
        // 必选插件（核心插件与下载工作台，required()=true）不可禁用、不在此呈现开关。从插件清单派生，避免与
        // 插件单一事实源漂移。分组顺序见 groups()（位于「下载」与「代理」之间）。
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            if (plugin.kind() != PluginKind.FEATURE || plugin.required()) {
                continue;
            }
            fields.add(ConfigFieldSpec.builder(
                            "plugins." + plugin.id() + ".enabled",
                            pluginText(plugin, plugin.displayNamespace(), plugin.displayName()),
                            BOOL, groupPlugins)
                    .defaultValue("true")
                    .help(pluginText(plugin, plugin.displayNamespace(), plugin.description()))
                    .build());
        }

        // 通知类型开关：每个 NotificationScenario 一项（默认开启），关闭后该类型的邮件与推送都不再发送
        // （由 NotificationService.notify 统一裁剪）。从枚举派生，避免与场景单一事实源漂移。
        for (NotificationScenario scenario : NotificationScenario.values()) {
            fields.add(ConfigFieldSpec.builder(
                            NotificationConfig.scenarioEnabledKey(scenario.id()),
                            message("gui.config.field.notification.scenario." + scenario.id() + ".label"),
                            BOOL, groupNotification)
                    .defaultValue("true")
                    .help(message("gui.config.field.notification.scenario." + scenario.id() + ".help"))
                    .hotReloadable()
                    .build());
        }
        return List.copyOf(fields);
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    /**
     * 禁用 ResourceBundle「回退到 JVM 默认 locale」——与 {@code WebI18nService} 同款：请求 locale 找不到时
     * 直接落到根 bundle（中文），不会在 en-US 系统上把中文界面错解析成英文。
     */
    private static final ResourceBundle.Control PLUGIN_BUNDLE_CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /**
     * 把插件声明的<b>纯 i18n key</b> 在指定 {@code namespace}（{@link PixivFeaturePlugin#displayNamespace()} 提供，
     * 与导航 {@code NavigationContribution.labelNamespace} 同「namespace 与 key 分离」模型）中解析为当前 GUI locale
     * 的文案：只在该 namespace（{@link PixivFeaturePlugin#i18n()} 贡献的 bundle，经插件自己的 ClassLoader 解析）中查
     * key；命中即返回；{@code namespace} 为 {@code null}（插件无展示 namespace）或缺失对应文案则返回 key 本身（守卫
     * 测试会暴露）。
     */
    private static String pluginText(PixivFeaturePlugin plugin, String namespace, String key) {
        if (namespace == null) {
            return key;
        }
        Locale locale = GuiMessages.currentLocale();
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        for (I18nContribution ns : plugin.i18n()) {
            if (!namespace.equals(ns.namespace())) {
                continue;
            }
            try {
                ResourceBundle bundle = ResourceBundle.getBundle(
                        ns.baseName(), locale, classLoader, PLUGIN_BUNDLE_CONTROL);
                if (bundle.containsKey(key)) {
                    return bundle.getString(key);
                }
            } catch (MissingResourceException ignored) {
                // 该 namespace 无对应 bundle
            }
        }
        return key;
    }

    private static String validateMaintenanceTime(String value) {
        return MaintenanceProperties.isValidTime(value)
                ? null
                : message("gui.config.validation.time-hh-mm");
    }

    private static String validatePositiveInt(String value) {
        try {
            return Integer.parseInt(value) >= 1 ? null : message("gui.config.validation.positive-int");
        } catch (NumberFormatException e) {
            return message("gui.config.validation.valid-int");
        }
    }
}
