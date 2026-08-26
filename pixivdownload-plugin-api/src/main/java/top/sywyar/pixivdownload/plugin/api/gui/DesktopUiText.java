package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * 宿主或插件拥有的本地化文本语义；它不描述任何工具包控件。
 *
 * @param namespace 可选插件 i18n namespace
 * @param key 稳定消息 key；原始文本使用空字符串
 * @param fallback 无法解析 key 时的回退文本
 * @param arguments 消息格式化参数
 */
public record DesktopUiText(String namespace, String key, String fallback, List<String> arguments) {
    private static final int MAX_TEXT_LENGTH = 16_384;

    /**
     * 规范化命名空间和消息键，并限制所有文本输入的大小。
     *
     * @param namespace 可选插件 i18n namespace
     * @param key 稳定消息 key；原始文本使用空字符串
     * @param fallback 无法解析 key 时的回退文本
     * @param arguments 消息格式化参数
     */
    public DesktopUiText {
        namespace = normalizeOptionalId(namespace, "namespace");
        key = normalizeKey(key);
        fallback = bounded(fallback, "fallback");
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        if (arguments.size() > 4_096) {
            throw new IllegalArgumentException("arguments is too large");
        }
        arguments = arguments.stream().map(value -> bounded(value, "argument")).toList();
        if (key.isBlank() && fallback.isBlank()) {
            throw new IllegalArgumentException("desktop text requires key or fallback");
        }
    }

    /**
     * 创建由宿主默认命名空间解析的文本语义。
     *
     * @param key 稳定消息键
     * @return 使用消息键自身作为回退值的文本语义
     */
    public static DesktopUiText key(String key) {
        return new DesktopUiText(null, key, key, List.of());
    }

    /**
     * 创建无需本地化解析的原始文本语义。
     *
     * @param text 原始显示文本
     * @return 原始文本语义
     */
    public static DesktopUiText raw(String text) {
        return new DesktopUiText(null, "", text, List.of());
    }

    /**
     * 创建由指定插件命名空间解析的文本语义。
     *
     * @param namespace 插件 i18n 命名空间
     * @param key 稳定消息键
     * @param fallback 无法解析时的回退文本
     * @return 保留插件 owner 语义的文本语义
     */
    public static DesktopUiText plugin(String namespace, String key, String fallback) {
        return new DesktopUiText(namespace, key, fallback, List.of());
    }

    private static String normalizeOptionalId(String value, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        requireId(normalized, name);
        return normalized;
    }

    private static String normalizeKey(String value) {
        String normalized = bounded(value, "key").trim();
        if (!normalized.isBlank()) requireId(normalized, "key");
        return normalized;
    }

    private static void requireId(String value, String name) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
    }

    private static String bounded(String value, String name) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }
}
