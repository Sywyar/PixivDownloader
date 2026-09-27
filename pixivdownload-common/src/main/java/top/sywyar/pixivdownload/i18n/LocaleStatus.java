package top.sywyar.pixivdownload.i18n;

import java.util.Locale;

/**
 * 语言清单中的语言状态。
 * <ul>
 *   <li>{@link #SOURCE}：开发源语言（当前唯一为 zh-CN），必须完整、非空、合法；对用户可见。</li>
 *   <li>{@link #SUPPORTED}：正式支持语言，必须达到 100% 覆盖率；当前 en-US 属于此状态，同时是全局回退语言。</li>
 *   <li>{@link #CANDIDATE}：渐进翻译中的候选语言，缺失 / 过期只报告、不阻止开发；已存在的翻译仍需合法。</li>
 *   <li>{@link #DISABLED}：不参与运行期语言菜单，也不参与正式覆盖率门禁；文件仍应可解析。</li>
 * </ul>
 */
public enum LocaleStatus {

    SOURCE,
    SUPPORTED,
    CANDIDATE,
    DISABLED;

    /**
     * 解析 locales.json 中的状态字符串；未知状态立即失败，保证启动 / 检查时不会静默接受错误清单。
     */
    public static LocaleStatus fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("locale status is missing");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "source" -> SOURCE;
            case "supported" -> SUPPORTED;
            case "candidate" -> CANDIDATE;
            case "disabled" -> DISABLED;
            default -> throw new IllegalArgumentException("unknown locale status: " + value);
        };
    }

    /** 是否进入普通用户的正式语言菜单。 */
    public boolean visible() {
        return this == SOURCE || this == SUPPORTED;
    }
}
