package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;

/**
 * 一个对某个 semver 提供方（核心 API 或被依赖插件）的版本要求，由插件描述符的 {@code requires} /
 * 依赖的 {@code versionSupport} 字段解析而来。只保留参与兼容判定的 {@code major.minor}（PATCH 不参与，
 * 见 {@link PluginApiVersion}）。
 *
 * <p>三态：
 * <ul>
 *   <li><b>未声明</b>（{@link #unspecified()}）：{@code requires} 为空 / 缺省。视为对任何版本都兼容
 *       （不约束即不会不兼容）。{@code present=false, valid=true}。</li>
 *   <li><b>有效</b>：成功解析出 {@code major.minor}。{@code present=true, valid=true}。</li>
 *   <li><b>无效</b>：声明了 {@code requires} 但无法解析（如非数字）。{@code present=true, valid=false}，
 *       这是描述符错误，{@link #isSatisfiedBy(int, int)} 恒为 {@code false}。</li>
 * </ul>
 *
 * <p>纯 JDK + {@code plugin.api}（仅 {@link PluginApiVersion}）。兼容规则<b>不在本类实现</b>，统一委托
 * {@link PluginApiVersion#isCompatible(int, int, int, int)}，避免复制版本判断逻辑。
 *
 * @param major   所需主版本号（未声明 / 无效时为 {@code -1}）
 * @param minor   所需次版本号（未声明 / 无效时为 {@code -1}）
 * @param present 是否声明了 {@code requires}
 * @param valid   声明的 {@code requires} 是否解析成功
 * @param raw     原始声明字符串（用于诊断，未声明时为 {@code null}）
 */
public record PluginApiRequirement(int major, int minor, boolean present, boolean valid, String raw) {

    private static final PluginApiRequirement UNSPECIFIED =
            new PluginApiRequirement(-1, -1, false, true, null);

    /** 未声明任何版本要求（兼容任何版本）。 */
    public static PluginApiRequirement unspecified() {
        return UNSPECIFIED;
    }

    /** 构造一个明确的 {@code major.minor} 版本要求。 */
    public static PluginApiRequirement of(int major, int minor) {
        return new PluginApiRequirement(major, minor, true, true, major + "." + minor);
    }

    /**
     * 解析 {@code requires} / {@code versionSupport} 声明。{@code null} / 空白 / {@code *}（不限版本标记）
     * → {@link #unspecified()}；否则在<b>仅容忍合法前导</b>（比较 / 范围运算符 {@code < > = ~ ^ !} 与空白、
     * 以及单个 {@code v} / {@code V} 前缀）后，要求紧接一个数字开头的版本号，取其 {@code MAJOR[.MINOR[.PATCH]]}
     * 的 {@code major.minor}（形如 {@code 1.0.0 & <2.0.0} 的范围取首段）。
     *
     * <p>「仅容忍合法前导」是相对旧实现的<b>收紧</b>：旧实现跳过<i>任意</i>非数字前缀，会把 {@code abc1.2} 之类
     * 误判为合法的 {@code 1.2}；现要求前导只能是运算符 / {@code v} 前缀，其后必须直接是数字，否则整串判为无效。
     *
     * @param raw 原始声明
     * @return 解析结果（永不返回 {@code null}）
     */
    public static PluginApiRequirement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNSPECIFIED;
        }
        String trimmed = raw.trim();
        if ("*".equals(trimmed)) {
            // 「不限版本」标记（如插件框架未声明 requires 时的默认值）：等同未声明、兼容任何版本。
            return UNSPECIFIED;
        }
        int len = trimmed.length();
        int start = 0;
        // 仅跳过合法前导：比较 / 范围运算符与其间空白。
        while (start < len && isLeadingOperator(trimmed.charAt(start))) {
            start++;
        }
        // 容忍单个 v / V 版本前缀。
        if (start < len && (trimmed.charAt(start) == 'v' || trimmed.charAt(start) == 'V')) {
            start++;
        }
        // 合法前导之后必须紧跟数字，否则不是版本号（如 abc1.2 / latest）。
        if (start >= len || !Character.isDigit(trimmed.charAt(start))) {
            return invalid(raw);
        }
        int end = start;
        while (end < len && (Character.isDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '.')) {
            end++;
        }
        String[] parts = trimmed.substring(start, end).split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
            return new PluginApiRequirement(major, minor, true, true, raw);
        } catch (NumberFormatException e) {
            return invalid(raw);
        }
    }

    /** 允许出现在版本号之前的比较 / 范围运算符（及其间空白），如 {@code >=}、{@code <}、{@code ~}、{@code ^}。 */
    private static boolean isLeadingOperator(char c) {
        return c == '<' || c == '>' || c == '=' || c == '~' || c == '^' || c == '!' || Character.isWhitespace(c);
    }

    private static PluginApiRequirement invalid(String raw) {
        return new PluginApiRequirement(-1, -1, true, false, raw);
    }

    /**
     * 给定提供方版本 {@code providedMajor.providedMinor}，是否满足本要求。未声明 → 恒满足；
     * 无效声明 → 恒不满足；否则委托 {@link PluginApiVersion#isCompatible(int, int, int, int)}。
     */
    public boolean isSatisfiedBy(int providedMajor, int providedMinor) {
        if (!present) {
            return true;
        }
        if (!valid) {
            return false;
        }
        return PluginApiVersion.isCompatible(providedMajor, providedMinor, major, minor);
    }

    /** 当前核心 API（{@link PluginApiVersion#MAJOR}/{@link PluginApiVersion#MINOR}）是否满足本要求。 */
    public boolean isSatisfiedByCurrentApi() {
        return isSatisfiedBy(PluginApiVersion.MAJOR, PluginApiVersion.MINOR);
    }

    /** 人类可读的版本要求（未声明时为 {@code "(unspecified)"}，无效时回显原始串）。 */
    public String display() {
        if (!present) {
            return "(unspecified)";
        }
        if (!valid) {
            return raw + " (unparseable)";
        }
        return major + "." + minor;
    }
}
