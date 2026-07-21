package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static top.sywyar.pixivdownload.gui.config.FieldType.*;

/**
 * 核心配置字段的单一事实源。
 * <p>
 * {@link #allFields()} 与 {@link #groups()} 在每次调用时按当前 locale 重新构建，
 * 这样 GUI 切换语言后再次构造 {@code ConfigPanel} 即可拿到本地化后的标签与分组名。
 * 不可改回 {@code static final List}：那样会在类加载时锁定 locale，热重载失效。
 */
public final class ConfigFieldRegistry {

    private ConfigFieldRegistry() {}

    private static final List<CoreGroupDefinition> CORE_GROUPS = List.of(
            new CoreGroupDefinition(GuiConfigGroups.SERVER, "gui.config.group.server", 100, true),
            new CoreGroupDefinition(GuiConfigGroups.DOWNLOAD, "gui.config.group.download", 200, true),
            new CoreGroupDefinition(GuiConfigGroups.PLUGINS, "gui.config.group.plugins", 300, true),
            new CoreGroupDefinition(GuiConfigGroups.PROXY, "gui.config.group.proxy", 400, true),
            new CoreGroupDefinition(GuiConfigGroups.MULTI_MODE, "gui.config.group.multi-mode", 500, true),
            new CoreGroupDefinition(GuiConfigGroups.GUEST_INVITE, "gui.config.group.guest-invite", 600, true),
            new CoreGroupDefinition(GuiConfigGroups.SECURITY, "gui.config.group.security", 700, true),
            new CoreGroupDefinition(GuiConfigGroups.MAINTENANCE, "gui.config.group.maintenance", 800, true),
            new CoreGroupDefinition(GuiConfigGroups.HTTPS, "gui.config.group.https", 900, true),
            new CoreGroupDefinition(GuiConfigGroups.UPDATE, "gui.config.group.update", 1000, true),
            new CoreGroupDefinition(GuiConfigGroups.SCHEDULE, "gui.config.group.schedule", 1100, true),
            new CoreGroupDefinition(GuiConfigGroups.AI, "gui.config.group.ai", 1200, true),
            new CoreGroupDefinition(GuiConfigGroups.NOTIFICATION, "gui.config.group.notification", 1300, true)
    );

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

    /** 通知分组名（邮件 / SMTP + 多通道推送，按当前 locale）。 */
    public static String groupNotification() {
        return message("gui.config.group.notification");
    }

    /** 全部分组名（按当前 locale，保持顺序）。 */
    public static List<String> groups() {
        return snapshot().groups();
    }

    /** 合并插件 GUI 配置 contribution 后的分组名（按当前 locale，保持顺序）。 */
    public static List<String> groups(GuiConfigContributionSnapshot pluginContributions) {
        return snapshot(pluginContributions).groups();
    }

    /** 核心配置字段（按当前 locale 重建标签/帮助文本）。 */
    public static List<ConfigFieldSpec> allFields() {
        return coreFields();
    }

    /** 合并插件 GUI 配置 contribution 后的配置字段。 */
    public static List<ConfigFieldSpec> allFields(GuiConfigContributionSnapshot pluginContributions) {
        return snapshot(pluginContributions).fields();
    }

    /** 核心字段快照。 */
    public static ConfigFieldSnapshot snapshot() {
        return snapshot(GuiConfigContributionSnapshot.empty());
    }

    /** 核心字段 + 已启用插件贡献字段的合并快照。 */
    public static ConfigFieldSnapshot snapshot(GuiConfigContributionSnapshot pluginContributions) {
        GuiConfigContributionSnapshot contributions = pluginContributions == null
                ? GuiConfigContributionSnapshot.empty()
                : pluginContributions;
        List<ConfigGroupSpec> mergedGroups = new ArrayList<>(coreGroupSpecs());
        Set<String> groupIds = mergedGroups.stream()
                .map(ConfigGroupSpec::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ConfigGroupSpec group : contributions.groups()) {
            if (group != null && groupIds.add(group.id())) {
                mergedGroups.add(group);
            }
        }
        List<ConfigFieldSpec> fields = new ArrayList<>(coreFields());
        fields.addAll(contributions.fields());
        Set<String> groupsWithFields = fields.stream()
                .filter(ConfigFieldSpec::contributesGroupVisibility)
                .flatMap(field -> groupKeys(field.groupId(), field.group()).stream())
                .collect(java.util.stream.Collectors.toSet());
        List<GuiConfigSectionSpec> sections = contributions.sections();
        Set<String> contributedGroupsWithSections = sections.stream()
                .filter(GuiConfigSectionSpec::contributesGroupVisibility)
                .flatMap(section -> groupKeys(section.groupId(), section.group()).stream())
                .collect(java.util.stream.Collectors.toSet());
        List<ConfigGroupSpec> visibleGroupSpecs = mergedGroups.stream()
                .filter(ConfigGroupSpec::visibleInTabs)
                .filter(group -> shouldShowGroup(group, groupsWithFields, contributedGroupsWithSections))
                .sorted(Comparator.comparingInt(ConfigGroupSpec::order))
                .toList();
        return ConfigFieldSnapshot.withGroupSpecs(visibleGroupSpecs, fields, sections, contributions.diagnostics());
    }

    private static boolean shouldShowGroup(ConfigGroupSpec group, Set<String> groupsWithFields,
                                           Set<String> contributedGroupsWithSections) {
        return groupsWithFields.contains(group.id())
                || groupsWithFields.contains(group.label())
                || contributedGroupsWithSections.contains(group.id())
                || contributedGroupsWithSections.contains(group.label());
    }

    private static Set<String> groupKeys(String groupId, String groupLabel) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (groupId != null && !groupId.isBlank()) {
            keys.add(groupId.trim());
        }
        if (groupLabel != null && !groupLabel.isBlank()) {
            keys.add(groupLabel);
        }
        return keys;
    }

    static boolean hasGroupId(String groupId) {
        return CORE_GROUPS.stream().anyMatch(group -> group.id().equals(groupId));
    }

    static Optional<String> groupLabel(String groupId) {
        return coreGroupSpecs().stream()
                .filter(group -> group.id().equals(groupId))
                .map(ConfigGroupSpec::label)
                .findFirst();
    }

    static Optional<Integer> groupOrder(String groupId) {
        return CORE_GROUPS.stream()
                .filter(group -> group.id().equals(groupId))
                .map(CoreGroupDefinition::order)
                .findFirst();
    }

    static Optional<ConfigGroupSpec> coreGroupSpecByLabel(String label) {
        return coreGroupSpecs().stream()
                .filter(group -> group.label().equals(label))
                .findFirst();
    }

    static Set<String> coreFieldKeys() {
        return coreFields().stream()
                .map(ConfigFieldSpec::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<ConfigGroupSpec> coreGroupSpecs() {
        return CORE_GROUPS.stream()
                .map(group -> new ConfigGroupSpec(group.id(), message(group.messageKey()),
                        group.order(), group.visibleInTabs()))
                .toList();
    }

    private static List<ConfigFieldSpec> coreFields() {
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

                ConfigFieldSpec.builder("database.maximum-pool-size", message("gui.config.field.database.maximum-pool-size.label"), INT, groupServer)
                        .defaultValue("28")
                        .help(message("gui.config.field.database.maximum-pool-size.help"))
                        .validator(v -> {
                            try {
                                int n = Integer.parseInt(v);
                                return n >= 8 ? null : message("gui.config.validation.min-eight-int");
                            } catch (NumberFormatException e) {
                                return message("gui.config.validation.valid-int");
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

                // ── 插件市场 / 受信 catalog（归入「插件」分组）──────────────────────
                // 插件启停由 Web 插件前端控制；桌面配置页只保留插件市场 / 仓库相关配置。
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
                        .build()
        );

        return baseFields;
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    /**
     * 把插件声明的<b>纯 i18n key</b> 在指定 {@code namespace}（{@link PixivFeaturePlugin#displayNamespace()} 提供，
     * 与导航 {@code NavigationContribution.labelNamespace} 同「namespace 与 key 分离」模型）中解析为当前 GUI locale
     * 的文案：只在该 namespace（{@link PixivFeaturePlugin#i18n()} 贡献的 bundle，经插件自己的 ClassLoader 解析）中查
     * key；命中即返回；{@code namespace} 为 {@code null}（插件无展示 namespace）或缺失对应文案则返回 key 本身（守卫
     * 测试会暴露）。
     */
    static String pluginText(PixivFeaturePlugin plugin, String namespace, String key) {
        return pluginText(plugin, plugin.getClass().getClassLoader(), namespace, key);
    }

    static String pluginText(PixivFeaturePlugin plugin, ClassLoader classLoader, String namespace, String key) {
        List<I18nContribution> i18nContributions = plugin.i18n();
        ClassLoader effectiveClassLoader = classLoader == null ? plugin.getClass().getClassLoader() : classLoader;
        return pluginText(i18nContributions, effectiveClassLoader, namespace, key);
    }

    static String pluginText(List<I18nContribution> i18nContributions, ClassLoader classLoader,
                             String namespace, String key) {
        return PluginContributionText.resolve(i18nContributions, classLoader, namespace, key);
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

    private record CoreGroupDefinition(String id, String messageKey, int order, boolean visibleInTabs) {
    }
}
