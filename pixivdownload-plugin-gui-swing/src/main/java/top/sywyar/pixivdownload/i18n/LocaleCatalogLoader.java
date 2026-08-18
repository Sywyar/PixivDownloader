package top.sywyar.pixivdownload.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 classpath {@code i18n/locales.json} 构建 {@link LocaleCatalog}。
 * <p>
 * 校验规则与 {@code scripts/i18n/lib/catalog.mjs} 共享同一组 fixture（Java / Node 必须同时拒绝同一非法清单）：
 * <ul>
 *   <li>{@code schemaVersion} 必须可识别（当前 1）；</li>
 *   <li>tag 合法且为规范形式（BCP 47 规范化与 Node Intl.getCanonicalLocales 一致：
 *       script 首字母大写、region 全大写；{@code _} / {@code -} 混用或大小写不规范都拒绝）；</li>
 *   <li>alias 规范化后不得重复；alias 不得与任意其他正式 tag 冲突；alias 与自己的 tag 重复拒绝；
 *       tag 不得与之前声明的 alias 冲突；声明顺序不影响冲突检查结果；</li>
 *   <li>{@code resourceSuffix} 先 trim 再检查唯一性；source locale 必须为空后缀；
 *       其他语言不得使用空后缀；后缀不得含路径分隔符、{@code ..} 或非法文件字符；</li>
 *   <li>direction 仅 {@code ltr} / {@code rtl}；exactly one source；default 必须可见；
 *       fallback 必须 source 或 supported；source / default / fallback 指针必须是规范 tag。</li>
 * </ul>
 */
public final class LocaleCatalogLoader {

    public static final String CATALOG_RESOURCE = "i18n/locales.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern ILLEGAL_SUFFIX =
            java.util.regex.Pattern.compile("[/\\\\:*?\"<>|\\u0000-\\u001f]|\\.\\.");

    private final ClassLoader classLoader;

    public LocaleCatalogLoader(ClassLoader classLoader) {
        this.classLoader = classLoader == null ? LocaleCatalog.class.getClassLoader() : classLoader;
    }

    public LocaleCatalog load() {
        try (InputStream in = classLoader.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing locale catalog resource: " + CATALOG_RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read locale catalog " + CATALOG_RESOURCE, e);
        }
    }

    /** 解析并严格校验目录 JSON；任何非法项都抛出 {@link IllegalArgumentException} / {@link IllegalStateException}。 */
    public LocaleCatalog parse(String json) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("locale catalog is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("locale catalog must be a JSON object");
        }

        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != LocaleCatalog.expectedSchemaVersion()) {
            throw new IllegalArgumentException("unsupported locale catalog schemaVersion: " + schemaVersion
                    + " (expected " + LocaleCatalog.expectedSchemaVersion() + ")");
        }

        JsonNode localesNode = root.get("locales");
        if (localesNode == null || !localesNode.isArray() || localesNode.isEmpty()) {
            throw new IllegalArgumentException("locale catalog requires a non-empty locales array");
        }
        List<LocaleDescriptor> descriptors = new ArrayList<>();
        for (JsonNode item : localesNode) {
            descriptors.add(parseLocale(item));
        }

        String sourceTag = requiredString(root, "sourceLocale");
        String defaultTag = requiredString(root, "defaultLocale");
        String fallbackTag = requiredString(root, "fallbackLocale");
        String cookieName = requiredString(root, "languageCookieName");
        String paramName = requiredString(root, "languageParameterName");

        LocaleDescriptor source = findByTag(descriptors, sourceTag, "sourceLocale");
        LocaleDescriptor defaultLocale = findByTag(descriptors, defaultTag, "defaultLocale");
        LocaleDescriptor fallback = findByTag(descriptors, fallbackTag, "fallbackLocale");

        return new LocaleCatalog(schemaVersion, source, defaultLocale, fallback, cookieName, paramName, descriptors);
    }

    private static LocaleDescriptor parseLocale(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new IllegalArgumentException("locale entry must be a JSON object");
        }
        String tag = requiredString(item, "tag");
        String canonical = canonicalTag(tag);
        if (canonical == null) {
            throw new IllegalArgumentException("invalid locale tag: " + tag);
        }
        if (!canonical.equals(tag.trim())) {
            throw new IllegalArgumentException("locale tag is not canonical (expected \"" + canonical
                    + "\", got \"" + tag + "\")");
        }
        String nativeName = requiredString(item, "nativeName");
        LocaleStatus status = LocaleStatus.fromJson(optionalString(item, "status"));
        String direction = optionalString(item, "direction");
        if (direction == null || !(direction.equals("ltr") || direction.equals("rtl"))) {
            throw new IllegalArgumentException("locale " + canonical + " has invalid direction: " + direction);
        }
        String suffix = optionalString(item, "resourceSuffix");
        if (suffix == null) {
            throw new IllegalArgumentException("locale " + canonical + " requires string field: resourceSuffix");
        }
        suffix = suffix.trim();
        if (ILLEGAL_SUFFIX.matcher(suffix).find()) {
            throw new IllegalArgumentException("locale " + canonical + " has illegal resourceSuffix \""
                    + suffix + "\" (path separators, \"..\" and invalid file characters are not allowed)");
        }
        if (status == LocaleStatus.SOURCE && !suffix.isEmpty()) {
            throw new IllegalArgumentException("source locale " + canonical + " must use an empty resourceSuffix");
        }
        if (status != LocaleStatus.SOURCE && suffix.isEmpty()) {
            throw new IllegalArgumentException("locale " + canonical + " must use a non-empty resourceSuffix");
        }

        List<String> aliases = new ArrayList<>();
        JsonNode aliasesNode = item.get("aliases");
        if (aliasesNode != null && !aliasesNode.isNull()) {
            if (!aliasesNode.isArray()) {
                throw new IllegalArgumentException("locale " + canonical + ": aliases must be an array");
            }
            for (JsonNode alias : aliasesNode) {
                if (alias == null || !alias.isTextual() || alias.asText().isBlank()) {
                    throw new IllegalArgumentException("locale " + canonical + ": alias must be a non-empty string");
                }
                aliases.add(alias.asText().trim());
            }
        }
        return new LocaleDescriptor(canonical, nativeName, suffix, status, direction, aliases);
    }

    /**
     * BCP 47 规范化（与 Node Intl.getCanonicalLocales 同 fixture 一致）：
     * 容忍 {@code _} / {@code -} 混用与大小写差异；非法 tag 返回 null。
     */
    static String canonicalTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String normalized = tag.trim().replace('_', '-');
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale == null || locale.getLanguage().isBlank()) {
            return null;
        }
        String canonical = locale.toLanguageTag();
        return canonical.isBlank() || canonical.equals("und") ? null : canonical;
    }

    private static LocaleDescriptor findByTag(List<LocaleDescriptor> descriptors, String tag, String field) {
        // 与 Node 一致：指针必须是规范 tag（精确匹配，不隐式规范化）
        String canonical = canonicalTag(tag);
        if (canonical == null || !canonical.equals(tag.trim())) {
            throw new IllegalArgumentException(field + " is not canonical: " + tag);
        }
        for (LocaleDescriptor descriptor : descriptors) {
            if (descriptor.tag().equals(canonical)) {
                return descriptor;
            }
        }
        throw new IllegalArgumentException(field + " does not match any locale tag: " + tag);
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("locale catalog requires integer field: " + field);
        }
        return value.asInt();
    }

    private static String requiredString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("locale catalog requires non-empty string field: " + field);
        }
        return value.asText().trim();
    }

    private static String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("locale catalog field must be a string: " + field);
        }
        return value.asText().trim();
    }
}
