package top.sywyar.pixivdownload.i18n;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 由唯一机器可读清单 {@code i18n/locales.json} 驱动的语言目录。
 * <p>
 * 这是全仓库（Java / 前端静态生成 / Node 检查器）唯一的语言事实来源：没有第二份需要人工同步的语言配置。
 * 构建时校验清单合法性，任何非法清单都会让启动失败而不是带病运行。
 * <p>
 * 运行期契约：
 * <ul>
 *   <li>source = 开发源语言（当前 zh-CN），是新增文案时直接维护的语言；</li>
 *   <li>default = 默认界面语言（当前 en-US），无匹配时的最终落点；</li>
 *   <li>fallback = 全局回退语言（当前 en-US），目标语言缺少翻译时的第一回退；</li>
 *   <li>回退链：目标语言 → fallback → source；</li>
 *   <li>匹配规则：精确规范化 tag → alias → 语言级匹配（仅当结果唯一）→ default。</li>
 * </ul>
 */
public final class LocaleCatalog {

    private static final int EXPECTED_SCHEMA_VERSION = 1;
    private static volatile LocaleCatalog defaultInstance;

    private final int schemaVersion;
    private final LocaleDescriptor source;
    private final LocaleDescriptor defaultLocale;
    private final LocaleDescriptor fallback;
    private final String languageCookieName;
    private final String languageParameterName;
    private final List<LocaleDescriptor> locales;
    private final List<LocaleDescriptor> visibleLocales;
    private final Map<String, LocaleDescriptor> byTag;
    private final Map<String, LocaleDescriptor> byAlias;

    LocaleCatalog(int schemaVersion,
                  LocaleDescriptor source,
                  LocaleDescriptor defaultLocale,
                  LocaleDescriptor fallback,
                  String languageCookieName,
                  String languageParameterName,
                  List<LocaleDescriptor> locales) {
        this.schemaVersion = schemaVersion;
        this.source = source;
        this.defaultLocale = defaultLocale;
        this.fallback = fallback;
        this.languageCookieName = languageCookieName;
        this.languageParameterName = languageParameterName;
        this.locales = List.copyOf(locales);
        this.visibleLocales = locales.stream().filter(LocaleDescriptor::visible).toList();

        Map<String, LocaleDescriptor> tags = new HashMap<>();
        Set<String> suffixes = new java.util.HashSet<>();
        long sourceCount = 0;
        // 第一遍：tag 与 suffix（tag 集合先完整建立，alias 冲突检查与声明顺序无关）
        for (LocaleDescriptor descriptor : locales) {
            if (tags.putIfAbsent(descriptor.tag(), descriptor) != null) {
                throw new IllegalArgumentException("duplicate locale tag: " + descriptor.tag());
            }
            if (!suffixes.add(descriptor.resourceSuffix())) {
                throw new IllegalArgumentException("conflicting resourceSuffix for locale "
                        + descriptor.tag() + ": " + descriptor.resourceSuffix());
            }
            if (descriptor.isSource()) {
                sourceCount++;
            }
        }
        // 第二遍：alias 规范化与冲突检查（对完整 tag 集合，声明顺序无关）
        Map<String, LocaleDescriptor> aliases = new HashMap<>();
        for (LocaleDescriptor descriptor : locales) {
            for (String alias : descriptor.aliases()) {
                String canonical = LocaleCatalogLoader.canonicalTag(alias);
                if (canonical == null) {
                    throw new IllegalArgumentException("locale " + descriptor.tag()
                            + ": alias is not a valid BCP 47 tag: \"" + alias + "\"");
                }
                if (canonical.equals(descriptor.tag())) {
                    throw new IllegalArgumentException("locale " + descriptor.tag()
                            + ": alias duplicates its own tag: \"" + alias + "\"");
                }
                LocaleDescriptor tagConflict = tags.get(canonical);
                if (tagConflict != null) {
                    throw new IllegalArgumentException("alias \"" + alias + "\" of locale " + descriptor.tag()
                            + " conflicts with the tag of locale " + tagConflict.tag());
                }
                LocaleDescriptor existing = aliases.putIfAbsent(canonical, descriptor);
                if (existing != null) {
                    throw new IllegalArgumentException("alias conflict for '" + alias
                            + "' between " + existing.tag() + " and " + descriptor.tag());
                }
            }
        }
        if (sourceCount != 1) {
            throw new IllegalArgumentException("exactly one source locale is required, found " + sourceCount);
        }
        if (source == null || !source.isSource()) {
            throw new IllegalArgumentException("sourceLocale must point to the source locale");
        }
        if (!locales.contains(defaultLocale)) {
            throw new IllegalArgumentException("defaultLocale not present in the catalog: "
                    + (defaultLocale == null ? null : defaultLocale.tag()));
        }
        if (!defaultLocale.visible()) {
            throw new IllegalArgumentException("defaultLocale must be a visible locale: "
                    + defaultLocale.tag());
        }
        if (!locales.contains(fallback)) {
            throw new IllegalArgumentException("fallbackLocale not present in the catalog: "
                    + (fallback == null ? null : fallback.tag()));
        }
        if (fallback.status() != LocaleStatus.SOURCE && fallback.status() != LocaleStatus.SUPPORTED) {
            throw new IllegalArgumentException("fallbackLocale must be source or supported: "
                    + fallback.tag());
        }
        this.byTag = Map.copyOf(tags);
        this.byAlias = Map.copyOf(aliases);
    }

    /**
     * 默认目录：从应用 classpath 的 {@code i18n/locales.json} 惰性加载并缓存。
     * 适用于非 Spring 场景（GUI 面板、日志解析器、启动期 locale 检测）。
     */
    public static LocaleCatalog defaultCatalog() {
        LocaleCatalog current = defaultInstance;
        if (current == null) {
            synchronized (LocaleCatalog.class) {
                current = defaultInstance;
                if (current == null) {
                    current = load(LocaleCatalog.class.getClassLoader());
                    defaultInstance = current;
                }
            }
        }
        return current;
    }

    /** 从指定 ClassLoader 加载 classpath 内的目录清单；非法清单立即抛出 {@link IllegalStateException}。 */
    public static LocaleCatalog load(ClassLoader classLoader) {
        return new LocaleCatalogLoader(classLoader).load();
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public static int expectedSchemaVersion() {
        return EXPECTED_SCHEMA_VERSION;
    }

    public LocaleDescriptor sourceLocale() {
        return source;
    }

    public LocaleDescriptor defaultLocale() {
        return defaultLocale;
    }

    public LocaleDescriptor fallbackLocale() {
        return fallback;
    }

    public String languageCookieName() {
        return languageCookieName;
    }

    public String languageParameterName() {
        return languageParameterName;
    }

    /** 全部语言（含 candidate / disabled），按清单声明顺序。 */
    public List<LocaleDescriptor> allLocales() {
        return locales;
    }

    /** 正式可见语言（source + supported），按清单声明顺序。 */
    public List<LocaleDescriptor> visibleLocales() {
        return visibleLocales;
    }

    /**
     * 匹配候选 tag 字符串：精确规范化 tag → alias → 语言级匹配（仅当结果唯一）→ 无匹配。
     * 大小写不敏感，且容忍 {@code _} / {@code -} 混用。
     */
    public Optional<LocaleDescriptor> match(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String normalized = candidate.trim().replace('_', '-');
        String canonical = canonicalTag(normalized);
        if (canonical == null) {
            return Optional.empty();
        }
        Optional<LocaleDescriptor> tagMatch = matchCanonicalTag(canonical);
        if (tagMatch.isPresent()) {
            return tagMatch;
        }
        return matchByLanguage(normalized);
    }

    /** {@link #match(String)} 的 {@link Locale} 形态：与字符串形态共享同一 alias 逻辑，杜绝漂移。 */
    public Optional<LocaleDescriptor> match(Locale candidate) {
        if (candidate == null || candidate.getLanguage().isBlank()) {
            return Optional.empty();
        }
        String canonical = canonicalTag(candidate.toLanguageTag());
        if (canonical == null) {
            return Optional.empty();
        }
        return matchCanonicalTag(canonical);
    }

    /**
     * 统一匹配管线：精确规范化 tag → alias → 唯一语言级匹配。
     * 供 {@link #match(String)} 与 {@link #match(Locale)} 共用，保证两条路径行为一致。
     */
    private Optional<LocaleDescriptor> matchCanonicalTag(String canonical) {
        LocaleDescriptor byTagMatch = byTag.get(canonical);
        if (byTagMatch != null) {
            return Optional.of(byTagMatch);
        }
        LocaleDescriptor aliasMatch = byAlias.get(canonical);
        if (aliasMatch != null) {
            return Optional.of(aliasMatch);
        }
        return matchByLanguage(canonical);
    }

    /** 匹配并归一化；无匹配时返回 default。 */
    public LocaleDescriptor resolve(String candidate) {
        return match(candidate).orElse(defaultLocale);
    }

    /** {@link #resolve(String)} 的 {@link Locale} 形态。 */
    public LocaleDescriptor resolve(Locale candidate) {
        return match(candidate).orElse(defaultLocale);
    }

    /**
     * 目标语言的运行期回退链：目标语言 → fallback → source（去重、保持顺序）。
     * effective bundle 按此链合并，合并顺序为 source → fallback → 目标语言（后者覆盖前者）。
     */
    public List<LocaleDescriptor> fallbackChain(LocaleDescriptor target) {
        if (target == null) {
            throw new IllegalArgumentException("fallbackChain target must not be null");
        }
        List<LocaleDescriptor> chain = java.util.stream.Stream.of(target, fallback, source)
                .filter(descriptor -> descriptor != null)
                .distinct()
                .toList();
        return chain;
    }

    private Optional<LocaleDescriptor> matchByLanguage(String candidate) {
        String language = languageOf(candidate);
        if (language == null || language.isEmpty()) {
            return Optional.empty();
        }
        List<LocaleDescriptor> languageMatches = locales.stream()
                .filter(descriptor -> descriptor.toLocale().getLanguage().equalsIgnoreCase(language))
                .toList();
        return languageMatches.size() == 1 ? Optional.of(languageMatches.get(0)) : Optional.empty();
    }

    private static String languageOf(String tag) {
        int first = tag.indexOf('-');
        return first < 0 ? tag : tag.substring(0, first);
    }

    /**
     * 规范化 BCP 47 tag：容忍 {@code _} / {@code -} 混用与大小写差异，返回 {@link Locale} 的规范化形态。
     */
    static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("locale tag is empty");
        }
        String normalized = canonicalTag(tag.trim().replace('_', '-'));
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("invalid locale tag: " + tag);
        }
        return normalized;
    }

    private static String canonicalTag(String tag) {
        return LocaleCatalogLoader.canonicalTag(tag);
    }
}
