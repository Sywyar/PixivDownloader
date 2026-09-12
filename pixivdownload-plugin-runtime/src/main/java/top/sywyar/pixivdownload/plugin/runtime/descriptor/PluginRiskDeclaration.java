package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.util.List;
import java.util.Objects;

/**
 * 从包内描述符取得的开发者能力声明，不代表权限授予或安全结论。
 *
 * @param present 描述符是否包含声明字段；缺失与显式空集合分别保留
 * @param signals 去重、稳定排序的原始 token，保留宿主尚不认识的值
 */
public record PluginRiskDeclaration(boolean present, List<String> signals) {
    public PluginRiskDeclaration {
        signals = Objects.requireNonNull(signals, "signals").stream().distinct().sorted().toList();
        if (!present && !signals.isEmpty()) {
            throw new IllegalArgumentException("absent risk declaration cannot contain signals");
        }
    }

    public static PluginRiskDeclaration absent() {
        return new PluginRiskDeclaration(false, List.of());
    }
}
