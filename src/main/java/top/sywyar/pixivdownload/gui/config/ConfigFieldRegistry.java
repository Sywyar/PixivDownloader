package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.util.List;

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

    /** 全部分组名（按当前 locale，保持顺序）。 */
    public static List<String> groups() {
        return List.of(
                message("gui.config.group.server"),
                message("gui.config.group.download"),
                message("gui.config.group.proxy"),
                message("gui.config.group.multi-mode"),
                message("gui.config.group.security"),
                message("gui.config.group.maintenance"),
                message("gui.config.group.https"),
                message("gui.config.group.update")
        );
    }

    /** 全部配置字段（按当前 locale 重建标签/帮助文本）。 */
    public static List<ConfigFieldSpec> allFields() {
        String groupServer = message("gui.config.group.server");
        String groupDownload = message("gui.config.group.download");
        String groupProxy = message("gui.config.group.proxy");
        String groupMultiMode = message("gui.config.group.multi-mode");
        String groupSecurity = message("gui.config.group.security");
        String groupMaintenance = message("gui.config.group.maintenance");
        String groupHttps = message("gui.config.group.https");
        String groupUpdate = message("gui.config.group.update");

        return List.of(

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

                ConfigFieldSpec.builder("update.auto-check", message("gui.config.field.update.auto-check.label"), BOOL, groupUpdate)
                        .defaultValue("true")
                        .help(message("gui.config.field.update.auto-check.help"))
                        .enabledWhen(snap -> snap.isTrue("update.enabled"))
                        .hotReloadable()
                        .build()
        );
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }
}
