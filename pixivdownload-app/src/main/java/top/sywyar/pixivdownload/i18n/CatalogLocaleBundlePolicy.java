package top.sywyar.pixivdownload.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 由 {@link LocaleCatalog} 生成的 host 语言策略：第一方运行路径的唯一策略。
 * <p>
 * - {@link #normalize(Locale)}：catalog 解析（精确 tag → alias → 唯一语言级 → default）；
 * - {@link #resourceSuffixChain(Locale)}：目标 → fallback → source 的 resourceSuffix 顺序
 *   （空字符串 = root 文件），由 catalog 的 descriptor / resourceSuffix 构造，
 *   resolver 不再猜测 {@code _ja_JP} / {@code _ja} / {@code _en}。
 */
public final class CatalogLocaleBundlePolicy implements LocaleBundlePolicy {

    private final LocaleCatalog catalog;

    public CatalogLocaleBundlePolicy(LocaleCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        this.catalog = catalog;
    }

    @Override
    public Locale normalize(Locale requested) {
        return catalog.resolve(requested).toLocale();
    }

    @Override
    public List<String> resourceSuffixChain(Locale requested) {
        LocaleDescriptor target = catalog.resolve(requested);
        List<String> chain = new ArrayList<>(3);
        for (LocaleDescriptor descriptor : catalog.fallbackChain(target)) {
            chain.add(descriptor.resourceSuffix());
        }
        return List.copyOf(chain);
    }
}
