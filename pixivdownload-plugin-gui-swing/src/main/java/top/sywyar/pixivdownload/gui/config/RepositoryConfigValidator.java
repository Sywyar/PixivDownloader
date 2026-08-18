package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 自定义插件仓库表单的<b>前置</b>校验（headless、可单测）。每个方法返回 i18n 错误 key（{@code null} = 通过），
 * 由 Swing 编辑对话框解析为本地化提示。
 *
 * <p><b>仅前置提示，非权威</b>：真正的安全裁定（HTTPS / SSRF / 重定向 / 大小 / sha256 / 签名）仍由后端
 * {@code PluginRepositoryRegistry} 与安装器执行，本类不放宽任何后端校验。保留字 / 代理策略枚举直接取自领域模型
 * （{@link PluginRepository} / {@link RepositoryProxyPolicy}），与后端单一事实源同步、不另抄一份常量。
 */
public final class RepositoryConfigValidator {

    private RepositoryConfigValidator() {
    }

    /** 单包 / 单清单字节上限的合理硬上限（10 GiB），既防数值溢出、也挡明显笔误。 */
    static final long MAX_BYTES_LIMIT = 10L * 1024 * 1024 * 1024;

    /** 超时的合理硬上限（1 小时，毫秒），防溢出 / 笔误。 */
    static final long MAX_TIMEOUT_MS = 60L * 60 * 1000;

    /**
     * 校验仓库 id：非空、非保留字（{@code official} / {@code configured}）、与<b>其它</b>仓库大小写归一后不重复。
     *
     * @param others 当前列表中除被编辑项以外的其它仓库（用于查重）
     */
    public static String validateId(String id, List<RepositoryConfigEntry> others) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            return "gui.config.market.repo.error.id-empty";
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (PluginRepository.OFFICIAL_ID.equals(normalized)
                || PluginRepository.LEGACY_CONFIGURED_ID.equals(normalized)) {
            return "gui.config.market.repo.error.id-reserved";
        }
        if (others != null) {
            for (RepositoryConfigEntry other : others) {
                if (normalized.equals(other.id().trim().toLowerCase(Locale.ROOT))) {
                    return "gui.config.market.repo.error.id-duplicate";
                }
            }
        }
        return null;
    }

    /**
     * 校验清单地址：必须是合法的<b>绝对 HTTPS</b> URL，且 host 非空。拒绝 http / file / jar / data / javascript /
     * 相对地址 / 空 host。
     */
    public static String validateManifestUrl(String manifestUrl) {
        return validateManifestUrl(manifestUrl, true);
    }

    /**
     * 校验清单地址：严格 HTTPS 时仅允许 {@code https}；自定义档关闭严格 HTTPS 时允许 {@code http}/{@code https}。
     * 其它协议、相对地址与空 host 始终拒绝。
     */
    public static String validateManifestUrl(String manifestUrl, boolean strictHttps) {
        String url = manifestUrl == null ? "" : manifestUrl.trim();
        if (url.isEmpty()) {
            return "gui.config.market.repo.error.url-empty";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "gui.config.market.repo.error.url-invalid";
        }
        if (!uri.isAbsolute()) {
            return "gui.config.market.repo.error.url-not-absolute";
        }
        String scheme = uri.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("http") && strictHttps) {
            return "gui.config.market.repo.error.url-not-https";
        }
        if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
            return "gui.config.market.repo.error.url-unsupported-scheme";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "gui.config.market.repo.error.url-no-host";
        }
        return null;
    }

    /**
     * 校验代理策略：必须取领域模型支持的固定枚举。未知策略不静默降级。
     */
    public static String validateProxyPolicy(String proxyPolicy) {
        String policy = proxyPolicy == null ? "" : proxyPolicy.trim();
        if (policy.isEmpty()) {
            return null; // 空 → 后端按默认 direct-strict 处理（编辑器下拉不会产生空值）
        }
        for (RepositoryProxyPolicy known : RepositoryProxyPolicy.values()) {
            if (known.configId().equalsIgnoreCase(policy)) {
                return null;
            }
        }
        return "gui.config.market.repo.error.proxy-policy-unknown";
    }

    /**
     * 校验「超时覆盖」文本：留空 = 继承全局默认（通过）；非空必须是正数毫秒、不超过合理上限（防溢出 / 笔误）。
     */
    public static String validateTimeoutOverride(String raw) {
        return validatePositiveOptional(raw, MAX_TIMEOUT_MS);
    }

    /**
     * 校验「大小上限覆盖」文本：留空 = 继承全局默认（通过）；非空必须是正数字节、不超过合理上限（防溢出 / 笔误）。
     */
    public static String validateSizeOverride(String raw) {
        return validatePositiveOptional(raw, MAX_BYTES_LIMIT);
    }

    private static String validatePositiveOptional(String raw, long upperBound) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null; // 继承全局默认
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return "gui.config.market.repo.error.number-invalid"; // 含溢出（超 long）
        }
        if (value <= 0) {
            return "gui.config.market.repo.error.number-not-positive";
        }
        if (value > upperBound) {
            return "gui.config.market.repo.error.number-too-large";
        }
        return null;
    }

    /** 把「覆盖」文本解析为长整（留空 / 非法 → 0 = 继承全局默认）。供编辑对话框落值到模型。 */
    public static long parseOverride(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
