package top.sywyar.pixivdownload.plugin.runtime.context;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个插件子 context 的 owner-scoped 属性快照。
 *
 * @param ownerProperties 只属于当前 owner、由宿主解密后提供的属性
 * @param sensitivePropertyKeys 当前宿主已知的全局敏感属性 key 集合
 */
public record PluginContextPropertySnapshot(
        Map<String, Object> ownerProperties,
        Set<String> sensitivePropertyKeys) {

    private static final PluginContextPropertySnapshot EMPTY =
            new PluginContextPropertySnapshot(Map.of(), Set.of());

    public PluginContextPropertySnapshot {
        Objects.requireNonNull(ownerProperties, "ownerProperties");
        Objects.requireNonNull(sensitivePropertyKeys, "sensitivePropertyKeys");

        LinkedHashMap<String, Object> propertiesCopy = new LinkedHashMap<>();
        ownerProperties.forEach((key, value) ->
                propertiesCopy.put(requirePropertyKey(key), Objects.requireNonNull(value, "property value")));

        LinkedHashSet<String> sensitiveKeysCopy = new LinkedHashSet<>();
        sensitivePropertyKeys.forEach(key -> sensitiveKeysCopy.add(requirePropertyKey(key)));
        if (!sensitiveKeysCopy.containsAll(propertiesCopy.keySet())) {
            throw new IllegalArgumentException("ownerProperties must be included in sensitivePropertyKeys");
        }

        ownerProperties = Map.copyOf(propertiesCopy);
        sensitivePropertyKeys = Set.copyOf(sensitiveKeysCopy);
    }

    public static PluginContextPropertySnapshot empty() {
        return EMPTY;
    }

    private static String requirePropertyKey(String key) {
        String normalized = Objects.requireNonNull(key, "property key").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("property key must not be blank");
        }
        return normalized;
    }
}
