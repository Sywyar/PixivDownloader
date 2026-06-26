package top.sywyar.pixivdownload.plugin.catalog.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 插件市场分类词表（与高保真设计对齐）。每个 catalog 条目声明一个分类 id；页面侧栏按分类筛选，分类计数由数据派生。
 *
 * <p>聚合项 {@code all}（{@link #AGGREGATE_ID}）只是「全部」的页面筛选入口，<b>不</b>作为某个条目的分类值、不入此枚举。
 * 条目分类 ∈ {@code translate / download / convert / notify / backup / security / ui / utility}。
 *
 * <p><b>稳定回退</b>：清单里出现未知 / 缺省分类 id 时，{@link #resolve(String)} 一律回落到 {@link #FALLBACK}
 * （{@code utility} 实用工具），使页面不因脏数据破版；{@link #isKnown(String)} 可供调用方区分「原值已知 / 已回退」。
 *
 * <p>纯 JDK 枚举，<b>不入 {@code plugin-api}</b>。
 */
public enum PluginCatalogCategory {

    TRANSLATE("translate"),
    DOWNLOAD("download"),
    CONVERT("convert"),
    NOTIFY("notify"),
    BACKUP("backup"),
    SECURITY("security"),
    UI("ui"),
    UTILITY("utility");

    /** 聚合筛选项 id（页面「全部」，不入清单单项、不是条目分类）。 */
    public static final String AGGREGATE_ID = "all";

    /** 未知 / 缺省分类的稳定回退（实用工具）。 */
    public static final PluginCatalogCategory FALLBACK = UTILITY;

    private final String id;

    PluginCatalogCategory(String id) {
        this.id = id;
    }

    /** 分类稳定 id（kebab-case，与界面语言无关）。 */
    public String id() {
        return id;
    }

    /** 精确解析分类 id（大小写 / 首尾空白不敏感）；未知 / 空 → 空。 */
    public static Optional<PluginCatalogCategory> fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (PluginCatalogCategory category : values()) {
            if (category.id.equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    /** 解析分类 id，未知 / 空一律回退到 {@link #FALLBACK}（稳定、永不为 {@code null}）。 */
    public static PluginCatalogCategory resolve(String raw) {
        return fromId(raw).orElse(FALLBACK);
    }

    /** 该原始 id 是否为已知分类（非聚合项、非未知值）。 */
    public static boolean isKnown(String raw) {
        return fromId(raw).isPresent();
    }
}
