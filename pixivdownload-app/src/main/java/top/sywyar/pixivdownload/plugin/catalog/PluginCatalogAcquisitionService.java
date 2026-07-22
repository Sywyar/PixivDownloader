package top.sywyar.pixivdownload.plugin.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 受信 catalog 安装编排：解析出目标 {@link PluginRepository} → 用<b>同一仓库</b>拉清单、按 {@code pluginId} + {@code version}
 * 选包 → 经 {@link PluginPackageDownloader} 按<b>该仓库</b>代理策略 / 超时 / 大小上限装配的 SSRF 安全客户端下载到临时文件 →
 * 交 {@link PluginInstallService} 以受信来源（{@link PluginPackageOrigin#forTrustedCatalog}）做完整性 + 结构 + 兼容校验并
 * 安全落盘 / 激活 → 删除临时文件。<b>不接受任意 URL</b>——下载地址只来自受信清单中按 id+version 选出的包。清单读取与包下载
 * <b>同源同仓库</b>（不存在「下载走全局严格客户端旁路」）；是否即时激活由统一安装服务按当前运行期能力决定。
 */
@Service
public class PluginCatalogAcquisitionService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogAcquisitionService.class);

    private final PluginCatalogService catalogService;
    private final PluginPackageDownloader downloader;
    private final PluginInstallService installService;
    private final PluginDependencyResolver dependencyResolver;

    public PluginCatalogAcquisitionService(PluginCatalogService catalogService,
                                           PluginPackageDownloader downloader,
                                           PluginInstallService installService,
                                           PluginDependencyResolver dependencyResolver) {
        this.catalogService = catalogService;
        this.downloader = downloader;
        this.installService = installService;
        this.dependencyResolver = dependencyResolver;
    }

    /** catalog 是否启用（供 GET 端点未启用短路、返回 disabled 视图而非错误）。 */
    public boolean isEnabled() {
        return catalogService.isEnabled();
    }

    /** 加载默认仓库的受信清单（未启用 → {@link PluginCatalogErrorCode#CATALOG_DISABLED}；不可用 → {@code CATALOG_UNAVAILABLE}）。 */
    public PluginCatalogManifest loadManifest() {
        return catalogService.load();
    }

    /**
     * 从<b>默认仓库</b>安装指定 id + version 的插件。catalog 禁用 / 不可用、未知 id、版本缺失、URL 不安全 / 阻断地址 /
     * 超限 / 下载失败 → {@link PluginCatalogException}；下载成功后的安装结局（含完整性不符 {@code REJECTED_INTEGRITY}、
     * 不兼容、Zip Slip 等）由 {@link PluginInstallReport} 承载（复用本地安装的结果模型）。
     */
    public PluginInstallReport install(String pluginId, String version) {
        PluginCatalogService.ResolvedCatalog catalog = catalogService.loadResolvedDefault();
        return installFrom(catalog.repository(), catalog.manifest(), pluginId, version);
    }

    /**
     * 从<b>指定仓库</b>（{@code repositoryId} 只能引用服务端已配置仓库，绝不接受任意 URL）安装指定 id + version 的插件。
     * 未知仓库 → {@link PluginCatalogErrorCode#UNKNOWN_REPOSITORY}、仓库禁用 → {@code REPOSITORY_DISABLED}、代理策略不支持 →
     * {@code PROXY_POLICY_UNSUPPORTED}、主开关关闭 → {@code CATALOG_DISABLED}、清单失败 → {@code CATALOG_UNAVAILABLE}、未知 id /
     * 版本缺失 / URL 不安全 / 阻断地址 / 超限 / 下载失败 → 对应稳定码；下载成功后的安装结局由 {@link PluginInstallReport} 承载。
     */
    public PluginInstallReport install(String repositoryId, String pluginId, String version) {
        PluginCatalogService.ResolvedCatalog catalog = catalogService.loadResolved(repositoryId);
        return installFrom(catalog.repository(), catalog.manifest(), pluginId, version);
    }

    /**
     * 在<b>给定仓库</b>的清单里按 id+version 选包 → 经<b>该仓库</b>装配的客户端 SSRF 安全下载到临时文件 → 受信完整性 /
     * 结构 / 兼容校验落盘 → 删临时文件。下载始终用 {@code repository}（清单的来源仓库），不退回默认 / 全局客户端。
     */
    private PluginInstallReport installFrom(PluginRepository repository, PluginCatalogManifest manifest,
                                            String pluginId, String version) {
        List<PluginDependencyInstallResult> dependencyInstallResults = new ArrayList<>();
        try {
            PluginInstallReport report = installFrom(repository, manifest, pluginId, version,
                    new ArrayDeque<>(), dependencyInstallResults);
            return report.withDependencyInstallResults(dependencyInstallResults);
        } catch (PluginCatalogException ex) {
            throw ex.withDependencyInstallResults(dependencyInstallResults);
        }
    }

    private PluginInstallReport installFrom(PluginRepository repository, PluginCatalogManifest manifest,
                                            String pluginId, String version, ArrayDeque<String> stack,
                                            List<PluginDependencyInstallResult> dependencyInstallResults) {
        if (stack.contains(pluginId)) {
            PluginDependencyRef dependency = new PluginDependencyRef(pluginId, version, false);
            return dependencyRejected(pluginId, version, List.of(dependency),
                    List.of(PluginDependencyProblem.cycle(dependency, cyclePath(stack, pluginId))), List.of());
        }
        PluginCatalogEntry entry = manifest.findEntry(pluginId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_PLUGIN, pluginId, version,
                        "plugin not found in catalog: " + pluginId));
        PluginCatalogPackage pkg = entry.findPackage(version).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.VERSION_NOT_FOUND, pluginId, version,
                        "version not found in catalog: " + pluginId + " " + version));

        stack.addLast(pluginId);
        try {
            List<PluginDependencyRef> declared = catalogDependencies(pkg);
            PluginInstallReport planned = installRequiredDependencies(repository, manifest,
                    pluginId, version, declared, stack, dependencyInstallResults, false);
            if (planned != null) {
                return planned;
            }

            Set<String> descriptorAttempts = new HashSet<>();
            while (true) {
                PluginInstallReport installed = downloadAndInstall(repository, pkg);
                if (installed.outcome() != PluginInstallOutcome.REJECTED_DEPENDENCY
                        || installed.dependencyProblems().isEmpty()) {
                    return installed;
                }
                List<PluginDependencyRef> descriptorDeps = installed.dependencyProblems().stream()
                        .filter(problem -> problem.reason() == PluginDependencyProblem.Reason.MISSING
                                || problem.reason() == PluginDependencyProblem.Reason.VERSION_UNSATISFIED)
                        .map(problem -> new PluginDependencyRef(
                                problem.pluginId(), problem.versionSupport(), problem.optional()))
                        .filter(dependency -> descriptorAttempts.add(dependency.pluginId()))
                        .toList();
                if (descriptorDeps.isEmpty()) {
                    return installed;
                }
                PluginInstallReport resolved = installRequiredDependencies(repository, manifest,
                        pluginId, version, descriptorDeps, stack, dependencyInstallResults, true);
                if (resolved != null) {
                    return resolved;
                }
            }
        } finally {
            stack.removeLast();
        }
    }

    private PluginInstallReport installRequiredDependencies(PluginRepository repository, PluginCatalogManifest manifest,
                                                            String pluginId, String version,
                                                            List<PluginDependencyRef> dependencies,
                                                            ArrayDeque<String> stack,
                                                            List<PluginDependencyInstallResult> dependencyInstallResults,
                                                            boolean authoritative) {
        List<PluginDependencyProblem> problems = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (PluginDependencyRef dependency : dependencies) {
            if (dependency.optional() || dependencyResolver.installedDependencySatisfied(dependency)) {
                continue;
            }
            if (stack.contains(dependency.pluginId())) {
                if (authoritative) {
                    problems.add(PluginDependencyProblem.cycle(dependency, cyclePath(stack, dependency.pluginId())));
                }
                continue;
            }
            Optional<PluginCatalogEntry> dependencyEntry = manifest.findEntry(dependency.pluginId());
            if (dependencyEntry.isEmpty()) {
                if (authoritative) {
                    problems.add(PluginDependencyProblem.catalogMissing(dependency));
                }
                continue;
            }
            Optional<PluginCatalogPackage> dependencyPackage =
                    selectDependencyPackage(dependencyEntry.get(), dependency);
            if (dependencyPackage.isEmpty()) {
                if (authoritative) {
                    problems.add(PluginDependencyProblem.catalogVersionUnsatisfied(dependency));
                }
                continue;
            }
            PluginInstallReport dependencyReport = installFrom(repository, manifest,
                    dependency.pluginId(), dependencyPackage.get().version(), stack, dependencyInstallResults);
            if (dependencyReport.recoveryBlocked()) {
                if (dependencyReport.accepted()) {
                    dependencyInstallResults.add(PluginDependencyInstallResult.from(dependencyReport));
                }
                PluginDependencyProblem blocked = PluginDependencyProblem.installFailed(
                        dependency, "RECOVERY_BLOCKED");
                List<String> blockedDiagnostics = dependencyReport.diagnostics().isEmpty()
                        ? List.of(blocked.detail()) : dependencyReport.diagnostics();
                return dependencyRejected(pluginId, version, dependencies,
                        List.of(blocked), blockedDiagnostics).withRecoveryBlocked();
            }
            if (!dependencyReport.accepted()) {
                if (authoritative) {
                    if (dependencyReport.dependencyProblems().isEmpty()) {
                        problems.add(PluginDependencyProblem.installFailed(dependency,
                                dependencyReport.outcome().name()));
                    } else {
                        problems.addAll(dependencyReport.dependencyProblems());
                    }
                    diagnostics.addAll(dependencyReport.diagnostics());
                }
                continue;
            }
            dependencyInstallResults.add(PluginDependencyInstallResult.from(dependencyReport));
            if (authoritative && !dependencyResolver.installedDependencySatisfied(dependency)) {
                problems.add(PluginDependencyProblem.versionUnsatisfied(dependency, dependencyReport.version()));
            }
        }
        if (problems.isEmpty()) {
            return null;
        }
        return dependencyRejected(pluginId, version, dependencies, problems, diagnostics);
    }

    private PluginInstallReport downloadAndInstall(PluginRepository repository, PluginCatalogPackage pkg) {

        // throws PROXY_POLICY_UNSUPPORTED / INSECURE_URL / BLOCKED_ADDRESS / TOO_LARGE / FAILED / INVALID
        Path temp = downloader.downloadToTemp(repository, pkg);
        try {
            PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                    repository.repositoryId(), repository.official(), pkg.expectedSizeBytes(), pkg.sha256(),
                    pkg.signature());
            return installService.installTrustedFile(temp, false, origin);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static Optional<PluginCatalogPackage> selectDependencyPackage(PluginCatalogEntry entry,
                                                                          PluginDependencyRef dependency) {
        return entry.packages().stream()
                .filter(pkg -> dependencyVersionSatisfied(dependency, pkg.version()))
                .max(Comparator.comparing(pkg -> PluginPackageVersion.parse(pkg.version())));
    }

    private static boolean dependencyVersionSatisfied(PluginDependencyRef dependency, String version) {
        PluginApiRequirement actual = PluginApiRequirement.parse(version);
        return dependency.requirement().isSatisfiedBy(actual.major(), actual.minor());
    }

    private static List<PluginDependencyRef> catalogDependencies(PluginCatalogPackage pkg) {
        if (pkg.dependencies().isEmpty()) {
            return List.of();
        }
        List<PluginDependencyRef> result = new ArrayList<>();
        for (String dependency : pkg.dependencies()) {
            result.addAll(PluginDependencyRef.parseList(dependency));
        }
        return List.copyOf(result);
    }

    private static PluginInstallReport dependencyRejected(String pluginId, String version,
                                                          List<PluginDependencyRef> dependencies,
                                                          List<PluginDependencyProblem> problems,
                                                          List<String> diagnostics) {
        return new PluginInstallReport(PluginInstallOutcome.REJECTED_DEPENDENCY,
                false, false, pluginId, version, null,
                dependencies.stream().map(PluginDependencyView::from).toList(),
                problems.stream().map(PluginDependencyProblem::pluginId).distinct().toList(),
                problems,
                diagnostics.isEmpty() ? problems.stream().map(PluginDependencyProblem::detail).toList() : diagnostics);
    }

    private static String cyclePath(ArrayDeque<String> stack, String repeated) {
        List<String> path = new ArrayList<>(stack);
        path.add(repeated);
        return String.join(" -> ", path);
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Failed to delete catalog download temp {}: {}", temp, e.toString());
        }
    }
}
