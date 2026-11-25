package top.sywyar.pixivdownload.plugin.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationService;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver.PlannedPackage;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVersion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 目录安装的有界只读计划；执行时重新取事实，指纹不是信任或验签凭据。 */
@Service
public class PluginCatalogInstallPreview {
    static final int MAX_PACKAGES = 128;
    static final int MAX_DEPTH = 32;
    private final PluginCatalogService catalog;
    private final PluginCatalogRevocationService revocations;
    private final ExternalPluginInstaller installer;
    private final PluginRuntimeManager runtime;
    private final PluginDependencyResolver dependencies;
    private final ExternalPluginLifecycleCoordinator coordinator;

    public PluginCatalogInstallPreview(PluginCatalogService catalog, PluginCatalogRevocationService revocations,
            ExternalPluginInstaller installer,
            PluginRuntimeManager runtime, ExternalPluginLifecycleCoordinator coordinator, PluginDependencyResolver dependencies) {
        this.catalog = catalog;
        this.revocations = revocations;
        this.installer = installer;
        this.runtime = runtime;
        this.dependencies = dependencies;
        this.coordinator = coordinator;
    }

    public View preview(String repositoryId, String pluginId, String version) {
        return reserve(() -> plan(repositoryId, pluginId, version).view());
    }

    public <T> T execute(String repositoryId, String pluginId, String version, String expected,
                         Function<Plan, T> action) {
        return reserve(() -> {
            Plan current = plan(repositoryId, pluginId, version);
            if (expected == null || !expected.equals(current.view().fingerprint())) {
                throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_CHANGED,
                        pluginId, version, "installation preview changed; review the current plan");
            }
            if (!current.view().conflicts().isEmpty()) {
                throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_BLOCKED,
                        pluginId, version, "installation preview contains unresolved conflicts");
            }
            return action.apply(current);
        });
    }

    private Plan plan(String repositoryId, String pluginId, String version) {
        var inventory = installer.snapshotInstalledWithProvenance(
                InstalledPluginInventorySnapshot.MAX_RECORDS, InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES);
        if (inventory.budgetExhausted()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_BLOCKED,
                    "installed provenance inventory is incomplete");
        }
        Map<String, InstalledPluginSnapshot> installed = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (var item : inventory.entries()) {
            if (installed.putIfAbsent(item.plugin().id(), item) != null) {
                conflicts.add(new Conflict("AMBIGUOUS_INSTALLATION", item.plugin().id(), List.of()));
            }
        }
        Map<String, Selection> selected = new LinkedHashMap<>();
        Map<String, Item> items = new LinkedHashMap<>();
        visit(catalog.resolvePackage(repositoryId, pluginId, version), installed, selected, items,
                conflicts, new ArrayList<>());
        for (var value : selected.values()) {
            for (var dependency : value.pkg().dependencyRefs()) {
                var target = selected.get(dependency.pluginId());
                if (!dependency.optional() && target != null && !satisfies(dependency, target.pkg().version())) {
                    conflicts.add(new Conflict("VERSION_CONFLICT", dependency.pluginId(),
                            List.of(value.source().entry().pluginId(), dependency.versionSupport())));
                }
            }
        }
        for (var item : inventory.entries()) {
            for (PluginDependencyRef dependency : item.plugin().descriptor().dependencies()) {
                var replacement = selected.get(dependency.pluginId());
                if (!dependency.optional() && replacement != null
                        && !satisfies(dependency, replacement.pkg().version())) {
                    conflicts.add(new Conflict("REVERSE_DEPENDENCY", dependency.pluginId(),
                            List.of(item.plugin().id(), dependency.versionSupport())));
                }
            }
        }
        if (conflicts.isEmpty()) {
            var packages = selected.values().stream().map(selection -> new PlannedPackage(
                    selection.source().entry().pluginId(), selection.pkg().version(),
                    selection.pkg().dependencyRefs())).toList();
            var blocked = dependencies.previewProblems(packages, installed.values().stream().map(InstalledPluginSnapshot::plugin).toList());
            blocked.forEach((id, problems) -> {
                if (!selected.containsKey(id) && installed.containsKey(id)) items.put(id, installedItem(installed.get(id), "BLOCKED"));
            });
            blocked.values().stream().flatMap(List::stream).distinct().forEach(problem ->
                    conflicts.add(new Conflict("DEPENDENCY_UNAVAILABLE", problem.pluginId(),
                            List.of(Objects.toString(problem.status(), problem.reason().name()), Objects.toString(problem.versionSupport(), "*")))));
        }
        // 只取原始事实；来源快照、加载代次和启停 epoch 的变化都会使旧确认失效。
        String facts = selected.values().toString() + inventory.entries().stream()
                .sorted(Comparator.comparing(item -> item.plugin().id())).toList()
                + runtime.loadedDescriptors().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()
                + runtime.packagePhases().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()
                + runtime.loadedDescriptors().keySet().stream().sorted()
                        .map(id -> id + ":" + runtime.generation(id)).toList()
                + coordinator.lifecycleMutationEpoch() + conflicts;
        return new Plan(new View(digest(facts), List.copyOf(items.values()), List.copyOf(conflicts)),
                List.copyOf(selected.values()));
    }

    private <T> T reserve(java.util.function.Supplier<T> action) {
        try {
            return coordinator.withMutationReservation(action);
        } catch (top.sywyar.pixivdownload.plugin.lifecycle.ClassifiedPluginLifecycleException failure) {
            if (failure.code() == top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode.BACKEND_STOP_FAILED)
                throw new PluginCatalogException(PluginCatalogErrorCode.BACKEND_STOP_FAILED, failure.getMessage());
            if (failure.code() != top.sywyar.pixivdownload.plugin.management.PluginManagementErrorCode.OPERATION_IN_PROGRESS) {
                throw failure;
            }
            throw new PluginCatalogException(PluginCatalogErrorCode.OPERATION_IN_PROGRESS, failure.getMessage());
        }
    }

    private void visit(PluginCatalogService.ResolvedPackage resolved,
            Map<String, InstalledPluginSnapshot> installed,
            Map<String, Selection> selected, Map<String, Item> items,
            List<Conflict> conflicts, List<String> stack) {
        String id = resolved.entry().pluginId();
        if (stack.contains(id)) {
            conflicts.add(new Conflict("CYCLE", id, List.copyOf(stack)));
            return;
        }
        if (selected.containsKey(id)) return;
        if (items.size() + stack.size() >= MAX_PACKAGES || stack.size() >= MAX_DEPTH) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_BLOCKED,
                    "dependency preview exceeds its package or depth limit");
        }
        var previous = installed.get(id);
        var pkg = previous == null ? resolved.pkg() : resolved.pkg().forInstalledArtifact(resolved.repository(), id,
                previous.plugin().version(), previous.artifactSha256());
        if (previous != null && pkg.conflictsWithInstalledArtifact(previous.plugin().version(), previous.artifactSha256())) {
            conflicts.add(new Conflict("SAME_VERSION_DIFFERENT_ARTIFACT", id, List.of(previous.artifactSha256(), pkg.sha256())));
        }
        if (!VersionRequirement.parseSdk(pkg.requiredSdk()).isSatisfiedByCurrentSdk()) {
            conflicts.add(new Conflict("INCOMPATIBLE_SDK", id, List.of(Objects.toString(pkg.requiredSdk(), "*"))));
        }
        if (revocations != null) revocations.requireInstallAllowed(resolved.repository(), id, pkg);
        stack.add(id);
        for (var dependency : pkg.dependencyRefs()) {
            if (dependency.optional()) continue;
            if (BuiltInPlugins.isBuiltIn(dependency.pluginId())) {
                conflicts.add(new Conflict("MISSING_DEPENDENCY", dependency.pluginId(), List.of(id, dependency.versionSupport())));
                continue;
            }
            var planned = selected.get(dependency.pluginId());
            if (planned != null) {
                if (!satisfies(dependency, planned.pkg().version())) {
                    conflicts.add(new Conflict("VERSION_CONFLICT", dependency.pluginId(), List.of(id, dependency.versionSupport())));
                }
                continue;
            }
            var present = installed.get(dependency.pluginId());
            if (present != null && satisfies(dependency, present.plugin().version())) {
                if (!items.containsKey(dependency.pluginId()) && items.size() + stack.size() >= MAX_PACKAGES) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.INSTALL_PREVIEW_BLOCKED,
                            "dependency preview exceeds its package limit");
                }
                items.putIfAbsent(dependency.pluginId(), installedItem(present, "REUSE"));
                continue;
            }
            try {
                var entry = catalog.loadEntry(resolved.repository().repositoryId(), dependency.pluginId());
                var snapshot = revocations == null ? null : revocations.requireCurrent(resolved.repository());
                var candidate = entry.packages().stream()
                        .filter(value -> satisfies(dependency, value.version()))
                        .filter(value -> VersionRequirement.parseSdk(value.requiredSdk()).isSatisfiedByCurrentSdk())
                        .filter(value -> revocations == null || revocations.allowsInstall(
                                resolved.repository(), entry.pluginId(), value, snapshot))
                        .max(Comparator.comparing(value -> PluginPackageVersion.parse(value.version())));
                if (candidate.isEmpty()) {
                    conflicts.add(new Conflict("MISSING_DEPENDENCY", dependency.pluginId(), List.of(id, dependency.versionSupport())));
                } else {
                    visit(catalog.resolvePackage(resolved.repository().repositoryId(), dependency.pluginId(),
                            candidate.get().version()), installed, selected, items, conflicts, stack);
                }
            } catch (PluginCatalogException failure) {
                if (failure.code() != PluginCatalogErrorCode.UNKNOWN_PLUGIN) throw failure;
                conflicts.add(new Conflict("MISSING_DEPENDENCY", dependency.pluginId(), List.of(id, dependency.versionSupport())));
            }
        }
        stack.remove(stack.size() - 1);
        String previousSource = source(previous);
        if (previous != null && previousSource != null && !previousSource.equals(resolved.repository().repositoryId())) {
            conflicts.add(new Conflict("SOURCE_CONFLICT", id, List.of(previousSource, resolved.repository().repositoryId())));
        }
        if (previous != null && PluginPackageVersion.parse(pkg.version())
                .compareTo(PluginPackageVersion.parse(previous.plugin().version())) < 0) {
            conflicts.add(new Conflict("DOWNGRADE", id, List.of(previous.plugin().version(), pkg.version())));
        }
        var consumers = installed.values().stream().filter(item -> item.plugin().descriptor().dependencies().stream()
                        .anyMatch(dep -> !dep.optional() && dep.pluginId().equals(id)))
                .map(item -> item.plugin().id()).sorted().toList();
        items.put(id, new Item(id, pkg.version(), resolved.repository().repositoryId(),
                resolved.repository().publisherDisplayName(), pkg.sha256(), pkg.expectedSizeBytes(),
                previous == null ? null : previous.plugin().version(), previousSource,
                previous == null ? "INSTALL" : "UPDATE", consumers, runtime.activeDependents(id),
                "PACKAGE_DECIDES"));
        selected.put(id, new Selection(resolved, pkg));
    }

    private static Item installedItem(InstalledPluginSnapshot item, String action) {
        return new Item(item.plugin().id(), item.plugin().version(), source(item),
                item.provenance() == null ? null : item.provenance().publisher(), item.artifactSha256(), item.artifactSizeBytes(),
                item.plugin().version(), source(item), action, List.of(), List.of(),
                item.plugin().descriptor().lifecyclePolicy().name());
    }

    private static String source(InstalledPluginSnapshot item) {
        return item == null || item.provenance() == null ? null : item.provenance().repositoryId();
    }

    static boolean satisfies(PluginDependencyRef dependency, String version) {
        var actual = VersionRequirement.parse(version);
        return dependency.requirement().isSatisfiedBy(actual.major(), actual.minor());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record View(String fingerprint, List<Item> packages, List<Conflict> conflicts) { }
    public record Item(String pluginId, String version, String repositoryId, @HttpApiNullable String publisher,
            @HttpApiNullable String sha256, @HttpApiNullable Long expectedSizeBytes,
            @HttpApiNullable String installedVersion, @HttpApiNullable String installedRepositoryId,
            @Schema(allowableValues = {"INSTALL", "UPDATE", "REUSE", "BLOCKED"}) String action,
            List<String> consumers,
            List<String> activeConsumers, String restartImpact) { }
    public record Conflict(String code, String pluginId, List<String> arguments) { }
    public record Selection(PluginCatalogService.ResolvedPackage source, PluginCatalogPackage pkg) { }
    public record Plan(View view, List<Selection> packages) { }
}
