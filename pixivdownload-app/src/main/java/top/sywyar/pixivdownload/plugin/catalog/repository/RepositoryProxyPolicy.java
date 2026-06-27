package top.sywyar.pixivdownload.plugin.catalog.repository;

import java.util.Locale;

/**
 * 插件仓库的代理策略（每仓库可配置）。决定从该仓库拉取清单 / 下载包时走哪条网络路径与安全档位。
 *
 * <ul>
 *   <li>{@link #DIRECT_STRICT}（默认）：直连、仅 https、禁重定向、拒非公网地址（SSRF）、size / sha256 必检。这是
 *       既有受信 catalog 的固有档位，<b>绝不放宽</b>。</li>
 *   <li>{@link #PROXY_TRUSTED}：仅对用户<b>显式信任</b>的仓库开放（如内嵌官方仓库）、经应用全局代理拉取，并按内置主机
 *       白名单跟随 GitHub release 资产的一跳重定向；完整性仍由安装器 sha256/size 逐字节兜底。配置与界面须给出风险提示。</li>
 *   <li>{@link #CUSTOM}：由仓库条目分别声明是否允许一跳重定向、是否仅 HTTPS、是否允许非公网地址、是否使用应用全局代理。
 *       这是显式高级档位，不改变两个预设档位的固定语义。</li>
 * </ul>
 *
 * <p>纯 JDK 枚举，<b>不入 {@code plugin-api}</b>（app 侧市场配置语义、非跨插件契约）。
 */
public enum RepositoryProxyPolicy {

    /** 直连严格档（仅 https + 禁重定向 + 拒非公网 + size/sha256 必检）。 */
    DIRECT_STRICT("direct-strict"),

    /** 经应用全局代理拉取 + 按内置主机白名单跟随一跳重定向，仅对用户显式信任的仓库开放（如内嵌官方仓库）。 */
    PROXY_TRUSTED("proxy-trusted"),

    /** 自定义网络约束；具体开关取自仓库条目。 */
    CUSTOM("custom");

    /** 默认策略（配置缺省 / 空白时采用）。 */
    public static final RepositoryProxyPolicy DEFAULT = DIRECT_STRICT;

    private final String configId;

    RepositoryProxyPolicy(String configId) {
        this.configId = configId;
    }

    /** 配置中使用的稳定标识（kebab-case，与界面语言无关）。 */
    public String configId() {
        return configId;
    }

    /**
     * 解析配置里的代理策略串。{@code null} / 空白 → {@link #DEFAULT}；大小写与首尾空白不敏感；无法识别 → {@code null}
     * （由调用方按「代理策略不支持」处理，<b>不</b>静默回落到直连，以免把未知意图当成更宽松或更严格的档位）。
     */
    public static RepositoryProxyPolicy fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (RepositoryProxyPolicy policy : values()) {
            if (policy.configId.equals(normalized)) {
                return policy;
            }
        }
        return null;
    }
}
