package top.sywyar.pixivdownload.i18n;

import org.springframework.context.support.AbstractMessageSource;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;

/**
 * 由 {@link LocaleCatalog} 回退链驱动的 {@code MessageSource}。
 * <p>
 * 与 {@code ReloadableResourceBundleMessageSource} 的差异：不做「默认语言回退」，也不在 exact bundle
 * 之外隐式合并 root 资源。每个语言只加载自己的物理资源文件（{@code baseName + resourceSuffix + .properties}），
 * 按「目标语言 → fallback → source」的回退链逐层查找（目标语言优先），实现
 * 目标语言 → en-US → zh-CN 的运行期回退契约。
 */
public final class CatalogMessageSource extends AbstractMessageSource {

    private final LocaleCatalog catalog;
    private final List<String> basenames;

    public CatalogMessageSource(LocaleCatalog catalog, String... basenames) {
        this.catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
        this.basenames = List.of(basenames == null ? new String[0] : basenames);
        // 与既有 ReloadableResourceBundleMessageSource 配置保持一致：缺 key 时返回 code 本身。
        setUseCodeAsDefaultMessage(true);
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String pattern = resolveCodeWithoutArguments(code, locale);
        return pattern == null ? null : createMessageFormat(pattern, locale);
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        if (code == null || code.isBlank()) {
            return null;
        }
        LocaleDescriptor target = catalog.resolve(locale);
        for (LocaleDescriptor descriptor : catalog.fallbackChain(target)) {
            for (String baseName : basenames) {
                String pattern = StaticBundleLoader.exact(baseName, descriptor).get(code);
                if (pattern != null) {
                    return pattern;
                }
            }
        }
        return null;
    }
}
