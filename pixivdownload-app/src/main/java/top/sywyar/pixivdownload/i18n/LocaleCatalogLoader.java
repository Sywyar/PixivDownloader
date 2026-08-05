package top.sywyar.pixivdownload.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 classpath {@code i18n/locales.json} 加载 {@link LocaleCatalog}。
 * <p>
 * 启动期校验（与 {@code scripts/i18n} 检查器的目录校验保持一致）：
 * <ul>
 *   <li>{@code schemaVersion} 必须可识别（当前为 1）；</li>
 *   <li>tag 合法且规范化、tag 不重复、alias 不冲突、{@code resourceSuffix} 不冲突；</li>
 *   <li>恰好存在一个 source，且 {@code sourceLocale} 指向它；</li>
 *   <li>default 与 fallback 均存在；fallback 必须是 source 或 supported；default 必须是可见语言；</li>
 *   <li>{@code nativeName} 非空、{@code direction} 只能是 {@code ltr} / {@code rtl}、未知状态立即失败。</li>
 * </ul>
 */
public final class LocaleCatalogLoader {

    public static final String CATALOG_RESOURCE = "i18n/locales.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    /** 解析并校验目录 JSON；任何非法项都抛出 {@link IllegalArgumentException} / {@link IllegalStateException}。 */
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
        String nativeName = requiredString(item, "nativeName");
        String resourceSuffix = requiredStringAllowEmpty(item, "resourceSuffix");
        LocaleStatus status = LocaleStatus.fromJson(optionalString(item, "status"));
        String direction = optionalString(item, "direction");
        List<String> aliases = new ArrayList<>();
        JsonNode aliasesNode = item.get("aliases");
        if (aliasesNode != null && !aliasesNode.isNull()) {
            if (!aliasesNode.isArray()) {
                throw new IllegalArgumentException("locale " + tag + ": aliases must be an array");
            }
            for (JsonNode alias : aliasesNode) {
                if (alias == null || !alias.isTextual() || alias.asText().isBlank()) {
                    throw new IllegalArgumentException("locale " + tag + ": alias must be a non-empty string");
                }
                aliases.add(alias.asText().trim());
            }
        }
        return new LocaleDescriptor(tag, nativeName, resourceSuffix, status, direction, aliases);
    }

    private static LocaleDescriptor findByTag(List<LocaleDescriptor> descriptors, String tag, String field) {
        for (LocaleDescriptor descriptor : descriptors) {
            if (descriptor.tag().equals(tag)) {
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

    /** 字段必须存在且为字符串，但允许空值（如源语言的空 resourceSuffix）。 */
    private static String requiredStringAllowEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("locale catalog requires string field: " + field);
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
