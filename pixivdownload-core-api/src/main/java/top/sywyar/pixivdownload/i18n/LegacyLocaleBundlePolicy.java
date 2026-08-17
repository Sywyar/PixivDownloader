package top.sywyar.pixivdownload.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 旧第三方插件的二进制兼容策略（legacy）。
 * <p>
 * 只保证历史约定：root 文件 = 开发源语言 zh-CN，{@code _en} = 全局回退语言 en-US；
 * 按 {@code [<lang>_<COUNTRY>, <lang>, en, root]} 顺序精确查找。
 * 这是 core-api 无法依赖应用 catalog 时对旧插件的兼容桥接，第一方代码一律使用
 * host catalog 构造的 {@link LocaleBundlePolicy} 实现（见应用模块的 catalog 策略类），
 * 不得新增本策略的调用点。
 * <p>
 * 本文件是硬编码语言守卫的集中例外（最小、带原因）：旧插件语义唯一入口。
 */
public final class LegacyLocaleBundlePolicy implements LocaleBundlePolicy {

    /** 全局回退语言（仓库约定：en-US 使用 {@code _en} 后缀文件）。 */
    private static final String FALLBACK_LANGUAGE = "en";

    /** root 文件的语言（仓库约定：无后缀文件 = 开发源语言 zh-CN）。 */
    private static final String ROOT_LANGUAGE = "zh";

    /**
     * 实例。
     */
    public static final LocaleBundlePolicy INSTANCE = new LegacyLocaleBundlePolicy();

    @Override
    public Locale normalize(Locale requested) {
        return requested == null ? Locale.getDefault() : requested;
    }

    @Override
    public List<String> resourceSuffixChain(Locale requested) {
        List<String> names = new ArrayList<>(3);
        if (requested != null) {
            String language = requested.getLanguage();
            if (language != null && !language.isBlank()) {
                String country = requested.getCountry();
                if (country != null && !country.isBlank()) {
                    names.add(language + "_" + country);
                }
                names.add(language);
                if (!ROOT_LANGUAGE.equalsIgnoreCase(language)
                        && !FALLBACK_LANGUAGE.equalsIgnoreCase(language)) {
                    names.add(FALLBACK_LANGUAGE);
                }
            }
        }
        names.add("");
        return List.copyOf(names);
    }
}
