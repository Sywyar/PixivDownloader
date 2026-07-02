package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 插件对另一个插件的依赖声明（中性载体）。映射自插件框架描述符的依赖项，但<b>不</b>泄露任何 PF4J 类型——
 * PF4J 收口在发现桥接内，描述符模型只用 {@code plugin.api} 契约与 JDK 类型。
 *
 * @param pluginId       被依赖插件的 id
 * @param versionSupport 被依赖插件的版本要求（如 {@code 1.0}；{@code *} / 空白表示不限版本）
 * @param optional       是否为可选依赖（可选依赖缺失不阻止依赖方启动）
 */
public record PluginDependencyRef(String pluginId, String versionSupport, boolean optional) {

    public PluginDependencyRef {
        Objects.requireNonNull(pluginId, "pluginId");
    }

    /**
     * 解析 PF4J {@code plugin.dependencies}（逗号分隔，每项 {@code pluginId} 或 {@code pluginId@versionSupport}，
     * pluginId 尾随 {@code ?} 表示可选）。
     */
    public static List<PluginDependencyRef> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<PluginDependencyRef> refs = new ArrayList<>();
        for (String token : raw.split(",")) {
            String dependency = token.trim();
            if (dependency.isEmpty()) {
                continue;
            }
            String pluginId;
            String versionSupport = "*";
            int at = dependency.indexOf('@');
            if (at >= 0) {
                pluginId = dependency.substring(0, at);
                if (dependency.length() > at + 1) {
                    versionSupport = dependency.substring(at + 1);
                }
            } else {
                pluginId = dependency;
            }
            boolean optional = false;
            if (pluginId.endsWith("?")) {
                optional = true;
                pluginId = pluginId.substring(0, pluginId.length() - 1);
            }
            refs.add(new PluginDependencyRef(pluginId.trim(), versionSupport.trim(), optional));
        }
        return List.copyOf(refs);
    }

    /** 解析 {@link #versionSupport} 为版本要求（{@code *} / 空白 → 不限版本）。 */
    public PluginApiRequirement requirement() {
        if (versionSupport == null || versionSupport.isBlank() || "*".equals(versionSupport.trim())) {
            return PluginApiRequirement.unspecified();
        }
        return PluginApiRequirement.parse(versionSupport);
    }
}
