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
     * 解析 {@code requires} / {@code versionSupport} 声明。{@code null} / 空白 → {@link #unspecified()}；
     * 否则尽量从开头提取 {@code MAJOR[.MINOR[.PATCH]]}（容忍前导的 {@code v} / 比较符号如 {@code >=}、以及形如
     * {@code 1.0.0 & <2.0.0} 的范围首段），取其 {@code major.minor}；提取不出前导版本号则判为无效。
     *
     * @param raw 原始声明
     * @return 解析结果（永不返回 {@code null}）
     */
    public static PluginApiRequirement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNSPECIFIED;
        }
        String trimmed = raw.trim();
        int start = 0;
        while (start < trimmed.length() && !Character.isDigit(trimmed.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < trimmed.length()
                && (Character.isDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '.')) {
            end++;
        }
        if (start >= end) {
            return invalid(raw);
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
