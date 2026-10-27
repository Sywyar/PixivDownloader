package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginClassSpace;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;

import java.util.HashSet;
import java.util.Set;

/** 在发布安装检视结果和加载插件前校验契约类空间，不让结果模型反向依赖校验职责。 */
public final class PluginClassSpaceVerifier {
    private PluginClassSpaceVerifier() { }

    public static PluginPackageInspection withClassSpace(PluginPackageInspection inspection, PluginClassSpace space) {
        contractPackages(space, inspection.descriptor());
        return new PluginPackageInspection(inspection.format(), inspection.descriptor(), inspection.innerJarEntry(),
                inspection.containsPrivateLibraries(), space);
    }

    public static Set<String> contractPackages(PluginClassSpace space, PluginDescriptor descriptor) {
        if (descriptor.contract() == null) {
            return Set.of();
        }
        String api = descriptor.contract().apiJar();
        Set<String> packages = space.packagesByArchive().getOrDefault(api, Set.of());
        if (packages.isEmpty() || packages.contains("") || !space.implicitClassPaths().isEmpty()
                || !descriptor.contract().sha256().equals(space.archiveDigests().get(api))
                || space.multiReleaseArchives().contains(api)) {
            throw unsafe("contract API requires a nonempty, digest-bound, non-multi-release library");
        }
        for (var archive : space.packagesByArchive().entrySet()) {
            if (!archive.getKey().equals(api) && !java.util.Collections.disjoint(packages, archive.getValue())) {
                throw unsafe("contract package is also present outside the declared API jar");
            }
        }
        return packages;
    }

    public static void requireCompatible(PluginClassSpace space, PluginDescriptor candidate,
                                         PluginDescriptor other, PluginClassSpace otherSpace) {
        Set<String> contracts = contractPackages(space, candidate);
        Set<String> otherContracts = contractPackages(otherSpace, other);
        if ((!contracts.isEmpty() && !otherSpace.implicitClassPaths().isEmpty())
                || (!otherContracts.isEmpty() && !space.implicitClassPaths().isEmpty())) {
            throw unsafe("contract class spaces cannot coexist with implicit archive class paths");
        }
        if (!java.util.Collections.disjoint(contracts, allPackages(otherSpace))
                || !java.util.Collections.disjoint(otherContracts, allPackages(space))) {
            throw unsafe("split contract package between " + candidate.id() + " and " + other.id());
        }
    }

    private static Set<String> allPackages(PluginClassSpace space) {
        Set<String> result = new HashSet<>();
        space.packagesByArchive().values().forEach(result::addAll);
        return result;
    }

    private static PluginPackageException unsafe(String message) {
        return new PluginPackageException(PluginPackageException.Reason.UNSAFE, message);
    }
}
