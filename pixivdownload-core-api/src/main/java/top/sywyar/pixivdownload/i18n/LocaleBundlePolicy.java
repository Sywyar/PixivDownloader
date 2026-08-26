package top.sywyar.pixivdownload.i18n;

import java.util.List;
import java.util.Locale;

/**
 * 语言 bundle 解析策略：由 host（应用侧语言目录）构造，resolver 只按策略
 * 返回的 suffix 顺序精确加载文件，不在 resolver 内部猜测 {@code _ja_JP} / {@code _ja} / {@code _en}。
 * <p>
 * 契约：
 * <ul>
 *   <li>{@link #normalize(Locale)}：把请求的 locale 归一化为本策略支持的正式 locale
 *       （alias、default、candidate 可见性等由 host catalog 决定）；</li>
 *   <li>{@link #resourceSuffixChain(Locale)}：目标语言 → fallback → source 的 resourceSuffix
 *       顺序（去重、保持顺序）；空字符串表示 root 文件（{@code baseName.properties}）；
 *       例如 host 目录为 target=ja-JP（suffix ja）、fallback=en-US（suffix en）、
 *       source=zh-CN（suffix 空）时返回 suffix 列表 ja → en → 空串；</li>
 *   <li>suffix 序列只表达「目标 → 回退 → 源」的文件查找顺序，不触发 JVM 默认 Locale 回退。</li>
 * </ul>
 * 该接口是 core-api 稳定契约：旧第三方插件走 {@link LegacyLocaleBundlePolicy}（只保证旧版
 * root=zh-CN + {@code _en}=en-US 约定），第一方代码一律使用 host catalog 构造的策略。
 */
public interface LocaleBundlePolicy {

    /**
     * 本策略正式支持的语言。旧实现默认只声明当前归一化语言；host catalog 实现返回全部可见语言。
     *
     * @return 方法返回的列表
     */
    default List<Locale> supportedLocales() {
        return List.of(normalize(Locale.getDefault()));
    }

    /**
     * 返回用于缺省输出的全局回退语言。旧实现保持当前语言，host catalog 与 legacy 策略提供明确值。
     *
     * @return 全局回退语言
     */
    default Locale fallbackLocale() {
        return normalize(Locale.getDefault());
    }

    /**
     * 把请求的 locale 归一化为策略支持的正式 locale。
     *
     * @param requested 请求值
     * @return 方法返回的 {@code Locale} 实例
     */
    Locale normalize(Locale requested);

    /**
     * 资源文件后缀查找链：目标 → fallback → source（去重）。
     * 空字符串 = root 文件；例如 host 目录为 target=ja-JP（suffix ja）、
     * fallback=en-US（suffix en）、source=zh-CN（suffix 空）时返回列表 ja → en → 空串。
     *
     * @param requested 请求值
     * @return 方法返回的列表
     */
    List<String> resourceSuffixChain(Locale requested);
}
