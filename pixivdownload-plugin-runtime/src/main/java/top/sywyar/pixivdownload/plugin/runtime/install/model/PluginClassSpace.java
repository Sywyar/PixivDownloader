package top.sywyar.pixivdownload.plugin.runtime.install.model;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 同一次有界归档扫描取得的类命名空间；只保存名称和摘要，不保存归档字节或 Class。 */
public record PluginClassSpace(Map<String, Set<String>> packagesByArchive,
                               Map<String, String> archiveDigests,
                               Set<String> multiReleaseArchives,
                               Set<String> implicitClassPaths) {
    public PluginClassSpace {
        packagesByArchive = packagesByArchive.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        archiveDigests = Map.copyOf(archiveDigests);
        multiReleaseArchives = Set.copyOf(multiReleaseArchives);
        implicitClassPaths = Set.copyOf(implicitClassPaths);
    }

    public static PluginClassSpace empty() {
        return new PluginClassSpace(Map.of(), Map.of(), Set.of(), Set.of());
    }
}
