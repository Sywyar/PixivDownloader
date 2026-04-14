package top.sywyar.pixivdownload.gui.config;

import java.util.List;

import static top.sywyar.pixivdownload.gui.config.FieldType.*;

/**
 * 所有配置字段的单一事实源（ALL_FIELDS）。
 * UI 根据此列表驱动渲染，无需逐字段硬编码控件。
 */
public final class ConfigFieldRegistry {

    private ConfigFieldRegistry() {}

    public static final List<ConfigFieldSpec> ALL_FIELDS = List.of(

            // ── 服务器 ─────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("server.port", "监听端口", PORT, "服务器")
                    .defaultValue("6999")
                    .help("服务器监听端口，修改后需重启")
                    .validator(v -> {
                        try {
                            int p = Integer.parseInt(v);
                            return (p >= 1 && p <= 65535) ? null : "端口范围：1–65535";
                        } catch (NumberFormatException e) {
                            return "请输入有效的端口号";
                        }
                    })
                    .build(),

            // ── 下载 ───────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("download.root-folder", "下载根目录", PATH_DIR, "下载")
                    .defaultValue("pixiv-download")
                    .help("图片保存的根目录（相对或绝对路径）")
                    .build(),

            ConfigFieldSpec.builder("download.user-flat-folder", "User 模式扁平目录", BOOL, "下载")
                    .defaultValue("false")
                    .help("false = 按用户名分目录；true = 与 N-Tab 相同的扁平结构")
                    .build(),

            // ── 代理 ───────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("proxy.enabled", "启用代理", BOOL, "代理")
                    .defaultValue("true")
                    .help("是否通过 HTTP 代理下载图片")
                    .build(),

            ConfigFieldSpec.builder("proxy.host", "代理地址", STRING, "代理")
                    .defaultValue("127.0.0.1")
                    .help("代理服务器 IP 或域名")
                    .enabledWhen(snap -> snap.isTrue("proxy.enabled"))
                    .build(),

            ConfigFieldSpec.builder("proxy.port", "代理端口", PORT, "代理")
                    .defaultValue("7890")
                    .help("代理服务器端口")
                    .enabledWhen(snap -> snap.isTrue("proxy.enabled"))
                    .validator(v -> {
                        try {
                            int p = Integer.parseInt(v);
                            return (p >= 1 && p <= 65535) ? null : "端口范围：1–65535";
                        } catch (NumberFormatException e) {
                            return "请输入有效的端口号";
                        }
                    })
                    .build(),

            // ── 多人模式 ────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("multi-mode.quota.enabled", "启用下载配额", BOOL, "多人模式")
                    .defaultValue("true")
                    .help("为每位用户设置下载数量限制")
                    .build(),

            ConfigFieldSpec.builder("multi-mode.quota.max-artworks", "每周期最多下载作品数", INT, "多人模式")
                    .defaultValue("50")
                    .help("每个用户在一个重置周期内最多可下载的作品数量")
                    .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                    .build(),

            ConfigFieldSpec.builder("multi-mode.quota.reset-period-hours", "配额重置周期（小时）", INT, "多人模式")
                    .defaultValue("24")
                    .help("经过多少小时后，用户配额自动重置")
                    .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                    .build(),

            ConfigFieldSpec.builder("multi-mode.quota.archive-expire-minutes", "压缩包有效期（分钟）", INT, "多人模式")
                    .defaultValue("60")
                    .help("达到配额后生成的压缩包下载链接的有效时间")
                    .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                    .build(),

            ConfigFieldSpec.builder("multi-mode.quota.limit-image", "单作品图片数上限", INT, "多人模式")
                    .defaultValue("0")
                    .help("0 = 不限制；设置后超出的作品按比例计算配额消耗")
                    .enabledWhen(snap -> snap.isTrue("multi-mode.quota.enabled"))
                    .build(),

            ConfigFieldSpec.builder("multi-mode.post-download-mode", "下载后处理模式", ENUM, "多人模式")
                    .defaultValue("pack-and-delete")
                    .enumValues("pack-and-delete", "never-delete", "timed-delete")
                    .help("pack-and-delete：打包后删除源文件；never-delete：保留；timed-delete：超时后删除")
                    .build(),

            ConfigFieldSpec.builder("multi-mode.delete-after-hours", "定时删除（小时）", INT, "多人模式")
                    .defaultValue("72")
                    .help("timed-delete 模式：下载后多少小时自动删除文件")
                    .enabledWhen(snap -> snap.equals("multi-mode.post-download-mode", "timed-delete"))
                    .build(),

            ConfigFieldSpec.builder("multi-mode.request-limit-minute", "每分钟最大请求数", INT, "多人模式")
                    .defaultValue("300")
                    .help("每个用户每分钟最多允许的 API 请求次数（0 = 不限制）")
                    .build(),

            // ── 安全 ───────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("setup.login-rate-limit-minute", "登录频率限制（次/分钟）", INT, "安全")
                    .defaultValue("10")
                    .help("每个 IP 每分钟最多允许的登录尝试次数（0 = 不限制）")
                    .build(),

            // ── HTTPS ──────────────────────────────────────────────────────────
            ConfigFieldSpec.builder("server.ssl.enabled", "启用 HTTPS", BOOL, "HTTPS")
                    .defaultValue("false")
                    .help("启用 HTTPS，需同时配置证书（PEM 或 JKS 二选一）")
                    .build(),

            ConfigFieldSpec.builder("server.ssl.certificate", "PEM 证书路径", PATH_FILE, "HTTPS")
                    .defaultValue("")
                    .help("PEM 格式证书文件（.pem），与 JKS 互斥，PEM 优先")
                    .enabledWhen(snap -> snap.isTrue("server.ssl.enabled"))
                    .build(),

            ConfigFieldSpec.builder("server.ssl.certificate-private-key", "PEM 私钥路径", PATH_FILE, "HTTPS")
                    .defaultValue("")
                    .help("PEM 格式私钥文件（.key 或 .pem）")
                    .enabledWhen(snap -> snap.isTrue("server.ssl.enabled")
                            && snap.notBlank("server.ssl.certificate"))
                    .build(),

            ConfigFieldSpec.builder("server.ssl.key-store-type", "JKS 证书类型", ENUM, "HTTPS")
                    .defaultValue("JKS")
                    .enumValues("JKS", "PKCS12")
                    .help("JKS 证书库类型")
                    .enabledWhen(snap -> snap.isTrue("server.ssl.enabled")
                            && snap.get("server.ssl.certificate").isBlank())
                    .build(),

            ConfigFieldSpec.builder("server.ssl.key-store", "JKS 证书库路径", PATH_FILE, "HTTPS")
                    .defaultValue("")
                    .help("JKS 格式证书库文件（.jks），与 PEM 互斥")
                    .enabledWhen(snap -> snap.isTrue("server.ssl.enabled")
                            && snap.get("server.ssl.certificate").isBlank())
                    .build(),

            ConfigFieldSpec.builder("server.ssl.key-store-password", "JKS 证书库密码", PASSWORD, "HTTPS")
                    .defaultValue("")
                    .help("JKS 证书库的访问密码")
                    .enabledWhen(snap -> snap.isTrue("server.ssl.enabled")
                            && snap.get("server.ssl.certificate").isBlank()
                            && snap.notBlank("server.ssl.key-store"))
                    .build(),

            ConfigFieldSpec.builder("ssl.http-redirect", "HTTP 自动重定向到 HTTPS", BOOL, "HTTPS")
                    .defaultValue("false")
                    .help("在 http-redirect-port 监听 HTTP 并 301 重定向到 HTTPS（需先配置 SSL）")
                    .build(),

            ConfigFieldSpec.builder("ssl.http-redirect-port", "HTTP 重定向监听端口", PORT, "HTTPS")
                    .defaultValue("80")
                    .help("HTTP 重定向使用的端口（默认 80）")
                    .enabledWhen(snap -> snap.isTrue("ssl.http-redirect"))
                    .build()
    );

    /** 所有分组名称（保持顺序） */
    public static final List<String> GROUPS = List.of(
            "服务器", "下载", "代理", "多人模式", "安全", "HTTPS"
    );
}
