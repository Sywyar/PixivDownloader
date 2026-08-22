package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 宿主拥有的桌面核心配置分组与字段目录。
 */
final class DesktopCoreConfigCatalog {
    private static final List<GuiConfigGroupContribution> GROUPS = List.of(
            group("interface", "gui.config.category.interface", 0),
            group(GuiConfigGroups.SERVER, "gui.config.group.server", 100),
            group(GuiConfigGroups.DOWNLOAD, "gui.config.group.download", 200),
            group(GuiConfigGroups.PLUGINS, "gui.config.group.plugins", 300),
            group(GuiConfigGroups.PROXY, "gui.config.group.proxy", 400),
            group(GuiConfigGroups.GUEST_INVITE, "gui.config.group.guest-invite", 600),
            group(GuiConfigGroups.SECURITY, "gui.config.group.security", 700),
            group(GuiConfigGroups.MAINTENANCE, "gui.config.group.maintenance", 800),
            group(GuiConfigGroups.HTTPS, "gui.config.group.https", 900),
            group(GuiConfigGroups.UPDATE, "gui.config.group.update", 1000),
            group(GuiConfigGroups.SCHEDULE, "gui.config.group.schedule", 1100)
    );

    private DesktopCoreConfigCatalog() {
    }

    static List<GuiConfigGroupContribution> groups() {
        return GROUPS;
    }

    static List<GuiConfigFieldContribution> fields(DesktopUiHost host) {
        List<GuiConfigFieldContribution> fields = new ArrayList<>();
        int order = 0;
        fields.add(core(
                "server.port",
                GuiConfigGroups.SERVER,
                GuiConfigFieldType.PORT,
                "6999",
                GuiConfigEffect.PROCESS_RESTART,
                1,
                65_535,
                order++
        ));
        fields.add(core(
                "database.maximum-pool-size",
                GuiConfigGroups.SERVER,
                GuiConfigFieldType.INT,
                "28",
                GuiConfigEffect.BACKEND_RESTART,
                8,
                null,
                order++
        ));
        fields.add(core(
                "debug.enabled",
                GuiConfigGroups.SERVER,
                GuiConfigFieldType.BOOL,
                "false",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++
        ));
        fields.add(core(
                "download.root-folder",
                GuiConfigGroups.DOWNLOAD,
                GuiConfigFieldType.PATH_DIR,
                "pixiv-download",
                GuiConfigEffect.PROCESS_RESTART,
                null,
                null,
                order++
        ));
        fields.add(core(
                "download.user-flat-folder",
                GuiConfigGroups.DOWNLOAD,
                GuiConfigFieldType.BOOL,
                "false",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        fields.add(core(
                "download.max-concurrent",
                GuiConfigGroups.DOWNLOAD,
                GuiConfigFieldType.INT,
                "10",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                null,
                order++
        ));
        fields.add(core(
                "plugin-catalog.enabled",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++
        ));
        fields.add(core(
                "plugin-catalog.official-repository-enabled",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("plugin-catalog.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "plugin-catalog.connect-timeout-ms",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.INT,
                "15000",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                null,
                order++
        ));
        fields.add(core(
                "plugin-catalog.read-timeout-ms",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.INT,
                "60000",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                null,
                order++
        ));
        fields.add(core(
                "plugin-catalog.max-manifest-bytes",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.INT,
                "1048576",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                null,
                order++
        ));
        fields.add(core(
                "plugin-catalog.max-package-bytes",
                GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.INT,
                "104857600",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                null,
                order++
        ));
        fields.add(core(
                "proxy.enabled",
                GuiConfigGroups.PROXY,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        fields.add(core(
                "proxy.host",
                GuiConfigGroups.PROXY,
                GuiConfigFieldType.STRING,
                host.defaultProxyHost(),
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("proxy.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "proxy.port",
                GuiConfigGroups.PROXY,
                GuiConfigFieldType.PORT,
                Integer.toString(host.defaultProxyPort()),
                GuiConfigEffect.HOT_RELOAD,
                1,
                65_535,
                order++,
                List.of(GuiConfigCondition.isTrue("proxy.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "guest-invite.request-limit-minute",
                GuiConfigGroups.GUEST_INVITE,
                GuiConfigFieldType.INT,
                "300",
                GuiConfigEffect.HOT_RELOAD,
                0,
                null,
                order++
        ));
        fields.add(core(
                "guest-invite.static-resource-request-limit-minute",
                GuiConfigGroups.GUEST_INVITE,
                GuiConfigFieldType.INT,
                "1200",
                GuiConfigEffect.HOT_RELOAD,
                0,
                null,
                order++
        ));
        fields.add(core(
                "guest-invite.tts-request-limit-minute",
                GuiConfigGroups.GUEST_INVITE,
                GuiConfigFieldType.INT,
                "30",
                GuiConfigEffect.HOT_RELOAD,
                0,
                null,
                order++
        ));
        fields.add(core(
                "setup.login-rate-limit-minute",
                GuiConfigGroups.SECURITY,
                GuiConfigFieldType.INT,
                "10",
                GuiConfigEffect.HOT_RELOAD,
                0,
                null,
                order++
        ));
        fields.add(core(
                "maintenance.enabled",
                GuiConfigGroups.MAINTENANCE,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        for (String day : List.of(
                "monday",
                "tuesday",
                "wednesday",
                "thursday",
                "friday",
                "saturday",
                "sunday"
        )) {
            String enabledKey = "maintenance." + day + ".enabled";
            fields.add(core(
                    enabledKey,
                    GuiConfigGroups.MAINTENANCE,
                    GuiConfigFieldType.BOOL,
                    day.equals("monday") ? "true" : "false",
                    GuiConfigEffect.HOT_RELOAD,
                    null,
                    null,
                    order++,
                    List.of(GuiConfigCondition.isTrue("maintenance.enabled")),
                    List.of(),
                    List.of(),
                    "gui.config.field." + enabledKey + ".label",
                    "gui.config.field.maintenance.day.enabled.help"
            ));
            String timeKey = "maintenance." + day + ".time";
            List<GuiConfigCondition> conditions = List.of(
                    GuiConfigCondition.isTrue(
                            "maintenance.enabled"),
                    GuiConfigCondition.isTrue(enabledKey)
            );
            fields.add(core(
                    timeKey,
                    GuiConfigGroups.MAINTENANCE,
                    GuiConfigFieldType.STRING,
                    host.defaultMaintenanceTime(),
                    GuiConfigEffect.HOT_RELOAD,
                    null,
                    null,
                    order++,
                    conditions,
                    conditions,
                    List.of(),
                    "gui.config.field." + timeKey + ".label",
                    "gui.config.field.maintenance.day.time.help"
            ));
        }
        fields.add(core(
                "ssl.domain",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.STRING,
                "localhost",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        fields.add(core(
                "server.ssl.enabled",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.BOOL,
                "false",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++
        ));
        fields.add(core(
                "ssl.type",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.ENUM,
                "pem",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("server.ssl.enabled")),
                List.of(),
                List.of("pem", "jks")
        ));
        fields.add(core(
                "server.ssl.certificate",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PATH_FILE,
                "",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                sslPemConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "pem")),
                List.of()
        ));
        fields.add(core(
                "server.ssl.certificate-private-key",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PATH_FILE,
                "",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                sslPemConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "pem")),
                List.of()
        ));
        fields.add(core(
                "server.ssl.key-store-type",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.ENUM,
                "JKS",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")),
                List.of("JKS", "PKCS12")
        ));
        fields.add(core(
                "server.ssl.key-store",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PATH_FILE,
                "",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")),
                List.of()
        ));
        fields.add(core(
                "server.ssl.key-store-password",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PASSWORD,
                "",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")),
                List.of()
        ));
        fields.add(core(
                "server.trusted-proxy-cidrs",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.STRING,
                "",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++
        ));
        fields.add(core(
                "ssl.http-redirect",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.BOOL,
                "false",
                GuiConfigEffect.BACKEND_RESTART,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("server.ssl.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "ssl.http-redirect-port",
                GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PORT,
                "80",
                GuiConfigEffect.BACKEND_RESTART,
                1,
                65_535,
                order++,
                List.of(
                        GuiConfigCondition.isTrue("server.ssl.enabled"),
                        GuiConfigCondition.isTrue("ssl.http-redirect")
                ),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "update.enabled",
                GuiConfigGroups.UPDATE,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        fields.add(core(
                "update.manifest-url",
                GuiConfigGroups.UPDATE,
                GuiConfigFieldType.STRING,
                host.defaultUpdateManifestUrl(),
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "update.nightly-manifest-url",
                GuiConfigGroups.UPDATE,
                GuiConfigFieldType.STRING,
                host.defaultNightlyUpdateManifestUrl(),
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++,
                List.of(
                        GuiConfigCondition.isTrue("update.enabled"),
                        GuiConfigCondition.isTrue("update.check-nightly")
                ),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "update.auto-check",
                GuiConfigGroups.UPDATE,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "update.check-nightly",
                GuiConfigGroups.UPDATE,
                GuiConfigFieldType.BOOL,
                Boolean.toString(host.currentVersionNightly()),
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.enabled",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.BOOL,
                "true",
                GuiConfigEffect.HOT_RELOAD,
                null,
                null,
                order++
        ));
        fields.add(core(
                "schedule.tick-interval-ms",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "60000",
                GuiConfigEffect.BACKEND_RESTART,
                1000,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.max-tasks",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "100",
                GuiConfigEffect.HOT_RELOAD,
                1,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.inbox-check-every",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "500",
                GuiConfigEffect.HOT_RELOAD,
                1,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.auth-failure-circuit-breaker",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "5",
                GuiConfigEffect.HOT_RELOAD,
                1,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.pending-max-attempts",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "5",
                GuiConfigEffect.HOT_RELOAD,
                1,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        fields.add(core(
                "schedule.overuse-defer-default-minutes",
                GuiConfigGroups.SCHEDULE,
                GuiConfigFieldType.INT,
                "60",
                GuiConfigEffect.HOT_RELOAD,
                60,
                null,
                order++,
                scheduleConditions(),
                List.of(),
                List.of()
        ));
        return List.copyOf(fields);
    }

    private static GuiConfigGroupContribution group(
            String id,
            String labelKey,
            int order
    ) {
        return new GuiConfigGroupContribution(
                id,
                labelKey,
                null,
                order,
                true
        );
    }

    private static GuiConfigFieldContribution core(
            String key,
            String group,
            GuiConfigFieldType type,
            String defaultValue,
            GuiConfigEffect effect,
            Integer minimum,
            Integer maximum,
            int order
    ) {
        return core(
                key,
                group,
                type,
                defaultValue,
                effect,
                minimum,
                maximum,
                order,
                List.of(),
                List.of(),
                type == GuiConfigFieldType.ENUM ? List.of(defaultValue) : List.of()
        );
    }

    private static GuiConfigFieldContribution core(
            String key,
            String group,
            GuiConfigFieldType type,
            String defaultValue,
            GuiConfigEffect effect,
            Integer minimum,
            Integer maximum,
            int order,
            List<GuiConfigCondition> enabled,
            List<GuiConfigCondition> visible,
            List<String> enumValues
    ) {
        return core(
                key,
                group,
                type,
                defaultValue,
                effect,
                minimum,
                maximum,
                order,
                enabled,
                visible,
                enumValues,
                "gui.config.field." + key + ".label",
                "gui.config.field." + key + ".help"
        );
    }

    private static GuiConfigFieldContribution core(
            String key,
            String group,
            GuiConfigFieldType type,
            String defaultValue,
            GuiConfigEffect effect,
            Integer minimum,
            Integer maximum,
            int order,
            List<GuiConfigCondition> enabled,
            List<GuiConfigCondition> visible,
            List<String> enumValues,
            String labelKey,
            String helpKey
    ) {
        return new GuiConfigFieldContribution(
                key,
                group,
                labelKey,
                helpKey,
                null,
                type,
                defaultValue,
                order,
                type == GuiConfigFieldType.PASSWORD,
                effect,
                enumValues,
                enabled,
                visible,
                minimum,
                maximum,
                true,
                Map.of()
        );
    }

    private static List<GuiConfigCondition> sslPemConditions() {
        return List.of(
                GuiConfigCondition.isTrue("server.ssl.enabled"),
                GuiConfigCondition.equalsTo("ssl.type", "pem")
        );
    }

    private static List<GuiConfigCondition> sslJksConditions() {
        return List.of(
                GuiConfigCondition.isTrue("server.ssl.enabled"),
                GuiConfigCondition.equalsTo("ssl.type", "jks")
        );
    }

    private static List<GuiConfigCondition> scheduleConditions() {
        return List.of(GuiConfigCondition.isTrue("schedule.enabled"));
    }
}
