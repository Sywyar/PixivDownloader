package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.util.HashSet;
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

    public Set<String> contractPackages(PluginDescriptor descriptor) {
        if (descriptor.contract() == null) {
            return Set.of();
        }
        String api = descriptor.contract().apiJar();
        Set<String> packages = packagesByArchive.getOrDefault(api, Set.of());
        if (packages.isEmpty() || packages.contains("") || !implicitClassPaths.isEmpty()
                || !descriptor.contract().sha256().equals(archiveDigests.get(api))
                || multiReleaseArchives.contains(api)) {
            throw unsafe("contract API requires a nonempty, digest-bound, non-multi-release library");
        }
        for (var archive : packagesByArchive.entrySet()) {
            if (!archive.getKey().equals(api) && !java.util.Collections.disjoint(packages, archive.getValue())) {
                throw unsafe("contract package is also present outside the declared API jar");
            }
        }
        return packages;
    }

    public void requireCompatible(PluginDescriptor candidate, PluginDescriptor other, PluginClassSpace otherSpace) {
        Set<String> contracts = contractPackages(candidate);
        Set<String> otherContracts = otherSpace.contractPackages(other);
        if ((!contracts.isEmpty() && !otherSpace.implicitClassPaths.isEmpty())
                || (!otherContracts.isEmpty() && !implicitClassPaths.isEmpty())) {
            throw unsafe("contract class spaces cannot coexist with implicit archive class paths");
        }
        if (!java.util.Collections.disjoint(contracts, otherSpace.allPackages())
                || !java.util.Collections.disjoint(otherContracts, allPackages())) {
            throw unsafe("split contract package between " + candidate.id() + " and " + other.id());
        }
    }

    private Set<String> allPackages() {
        Set<String> result = new HashSet<>();
        packagesByArchive.values().forEach(result::addAll);
        return result;
    }

    private static PluginPackageException unsafe(String message) {
        return new PluginPackageException(PluginPackageException.Reason.UNSAFE, message);
    }
}
