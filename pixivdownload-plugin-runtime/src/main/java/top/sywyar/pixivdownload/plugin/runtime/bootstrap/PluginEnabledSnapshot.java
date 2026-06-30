package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动前插件启用配置的不可变快照（中性、纯 JDK）。
 *
 * <p>承载 config.yaml 中 {@code plugins.<featureId>.enabled} 的解析结果：未在快照中标记为禁用的 feature id
 * 一律视为启用（与 {@code PluginToggleProperties} 缺项默认启用语义一致）。本类只<b>记录</b>启用 / 禁用事实，
 * 不影响 PF4J 扫描——已安装但配置禁用的插件仍允许 PF4J 加载、诊断、出现在 inventory 中；禁用态仅供启动前消费者
 * 按需读取（当前运行期尚不消费该快照）。
 *
 * <p>本类是 plugin-runtime bootstrap 的中性载体，<b>不</b>读 config.yaml、<b>不</b>依赖 Spring / ConfigFileEditor——
 * 由 app 侧把已解析的原始 {@code plugins} 段（或禁用集合）传入。配置读取失败由调用方收敛为 {@link #empty()} 并附诊断。
 */
public final class PluginEnabledSnapshot {

    private final Set<String> disabledFeatureIds;
    private final List<String> diagnostics;

    private PluginEnabledSnapshot(Collection<String> disabledFeatureIds, Collection<String> diagnostics) {
        this.disabledFeatureIds = Set.copyOf(disabledFeatureIds);
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** 全部启用、无诊断的空快照（安全回退）。 */
    public static PluginEnabledSnapshot empty() {
        return new PluginEnabledSnapshot(Set.of(), List.of());
    }

    /** 直接按已知禁用 feature id 集合构造（调用方已解析完毕）。 */
    public static PluginEnabledSnapshot ofDisabled(Collection<String> disabledFeatureIds, Collection<String> diagnostics) {
        return new PluginEnabledSnapshot(
                disabledFeatureIds == null ? Set.of() : new TreeSet<>(disabledFeatureIds),
                diagnostics == null ? List.of() : diagnostics);
    }

    /**
     * 从原始 {@code plugins} 段解析：每个 feature id 的值可为 {@code null}（缺项→启用）、{@code Boolean}、
     * 含 {@code enabled} 键的 {@code Map}、或字符串 {@code "true"}/{@code "false"}。非布尔 / 非法值不致命——
     * 记一条诊断并按缺项默认启用处理。结果不可变。
     *
     * @param rawPluginsSection  config.yaml {@code plugins:} 段的原始映射（id→值）；可为 {@code null}
     * @param sourceDescription  诊断里标注的来源描述（如 {@code "config.yaml"}），仅用于诊断文本
     */
    public static PluginEnabledSnapshot of(Map<String, ?> rawPluginsSection, String sourceDescription) {
        String source = (sourceDescription == null || sourceDescription.isBlank()) ? "plugins" : sourceDescription;
        if (rawPluginsSection == null || rawPluginsSection.isEmpty()) {
            return empty();
        }
        Set<String> disabled = new TreeSet<>();
        List<String> diagnostics = new ArrayList<>();
        for (Map.Entry<String, ?> entry : rawPluginsSection.entrySet()) {
            String featureId = entry.getKey();
            EnabledParse parse = parseEnabled(featureId, entry.getValue(), source);
            if (parse.diagnostic() != null) {
                diagnostics.add(parse.diagnostic());
            }
            if (!parse.enabled()) {
                disabled.add(featureId);
            }
        }
        return new PluginEnabledSnapshot(disabled, diagnostics);
    }

    private static EnabledParse parseEnabled(String featureId, Object value, String source) {
        if (value == null) {
            return EnabledParse.enabledByDefault();
        }
        if (value instanceof Map<?, ?> map) {
            return parseBooleanish(map.get("enabled"), featureId, source);
        }
        return parseBooleanish(value, featureId, source);
    }

    /** 解析单个 enabled 值：Boolean、字符串 {@code true}/{@code false}（大小写无关）合法；其余记诊断并按默认启用。 */
    private static EnabledParse parseBooleanish(Object enabled, String featureId, String source) {
        if (enabled == null) {
            return EnabledParse.enabledByDefault();
        }
        if (enabled instanceof Boolean b) {
            return new EnabledParse(b, null);
        }
        if (enabled instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return new EnabledParse(true, null);
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return new EnabledParse(false, null);
            }
            return new EnabledParse(true, source + ": plugins." + featureId
                    + ".enabled 非法值（" + trimmed + "），按默认启用处理");
        }
        return new EnabledParse(true, source + ": plugins." + featureId
                + ".enabled 非布尔值（" + describe(enabled) + "），按默认启用处理");
    }

    private static String describe(Object value) {
        return value == null ? "null" : (value.getClass().getSimpleName() + ":" + value);
    }

    /** 给定 feature id 是否启用；未标记为禁用者一律返回 true（缺项默认启用）。 */
    public boolean isEnabled(String featureId) {
        return featureId == null || !disabledFeatureIds.contains(featureId);
    }

    /** 被显式禁用的 feature id（不可变、按自然序）。 */
    public Set<String> disabledFeatureIds() {
        return disabledFeatureIds;
    }

    /** 解析期产生的诊断条目（非法值 / 不支持类型等；不可变）。 */
    public List<String> diagnostics() {
        return diagnostics;
    }

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    private record EnabledParse(boolean enabled, String diagnostic) {
        static EnabledParse enabledByDefault() {
            return new EnabledParse(true, null);
        }
    }
}
