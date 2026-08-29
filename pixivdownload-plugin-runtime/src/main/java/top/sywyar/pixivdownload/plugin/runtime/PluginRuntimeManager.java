package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactLoadPlan;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactMaterializer;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactWorkspaceOwner;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentDiagnostics;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginStartupResourceBudget;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PreparedPluginArtifact;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackageIndex;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackageIndex.Entry;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.pf4j.HostControlledPluginManager;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;

/**
 * PF4J 外置插件物理生命周期封装。启动扫描与运行期变更共用单包 load/start/stop/unload 原语，
 * app 侧不会接触任何 PF4J 类型。
 */
public class PluginRuntimeManager {

    // 所有运行期包变更必须复用本类的单包原语，禁止在 app 侧直接操作 PF4J manager。

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeManager.class);
    private static final PluginPackageLimits PRODUCTION_PACKAGE_LIMITS = PluginPackageLimits.defaults();
    static final int MAX_STARTUP_VERIFICATION_ENTRIES = 32_000;
    static final long MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES = 384L * 1024L * 1024L;
    static final long MAX_STARTUP_PROVENANCE_BYTES = 64L * 1024L * 1024L;

    private final Path pluginsRoot;
    private final PluginRuntimeLayout layout;
    private final PluginArtifactWorkspaceOwner workspaceOwner;
    private final PluginArtifactMaterializer materializer;
    private PluginArtifactVerificationService verificationService;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private PluginArtifactAdmissionPolicy admissionPolicy = PluginArtifactAdmissionPolicy.allowAll();
    private final BooleanSupplier developmentModeEnabled;
    private final PluginProvenanceStore provenanceStore;
    private final int maximumStartupVerificationEntries;
    private final long maximumStartupVerificationUncompressedBytes;
    private final long maximumStartupProvenanceBytes;
    private final PluginRuntimePackageIndex packageIndex = new PluginRuntimePackageIndex();

    private volatile PluginManager pluginManager;
    private volatile PluginRuntimeStatus status;
    private PluginDevelopmentArtifacts.DevelopmentCacheSession developmentCacheSession;

    public PluginRuntimeManager(Path pluginsRoot) {
        this(pluginsRoot, new PluginSupplyChainVerifier());
    }

    PluginRuntimeManager(Path pluginsRoot,
                         int maximumStartupVerificationEntries,
                         long maximumStartupVerificationUncompressedBytes) {
        this(pluginsRoot, fixedVerifier(new PluginSupplyChainVerifier()),
                maximumStartupVerificationEntries, maximumStartupVerificationUncompressedBytes,
                MAX_STARTUP_PROVENANCE_BYTES);
    }

    PluginRuntimeManager(Path pluginsRoot,
                         int maximumStartupVerificationEntries,
                         long maximumStartupVerificationUncompressedBytes,
                         long maximumStartupProvenanceBytes) {
        this(pluginsRoot, fixedVerifier(new PluginSupplyChainVerifier()),
                maximumStartupVerificationEntries, maximumStartupVerificationUncompressedBytes,
                maximumStartupProvenanceBytes, PluginDevelopmentArtifacts::enabled);
    }

    protected PluginRuntimeManager(Path pluginsRoot, BooleanSupplier developmentModeEnabled) {
        this(pluginsRoot, fixedVerifier(new PluginSupplyChainVerifier()),
                MAX_STARTUP_VERIFICATION_ENTRIES, MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES,
                MAX_STARTUP_PROVENANCE_BYTES, developmentModeEnabled);
    }

    protected PluginRuntimeManager(Path pluginsRoot,
                                   int maximumStartupVerificationEntries,
                                   long maximumStartupVerificationUncompressedBytes,
                                   long maximumStartupProvenanceBytes,
                                   BooleanSupplier developmentModeEnabled) {
        this(pluginsRoot, fixedVerifier(new PluginSupplyChainVerifier()),
                maximumStartupVerificationEntries, maximumStartupVerificationUncompressedBytes,
                maximumStartupProvenanceBytes, developmentModeEnabled);
    }

    public PluginRuntimeManager(Path pluginsRoot, PluginSupplyChainVerifier verifier) {
        this(pluginsRoot, fixedVerifier(verifier));
    }

    public PluginRuntimeManager(Path pluginsRoot,
                                Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this(pluginsRoot, verifierResolver, MAX_STARTUP_VERIFICATION_ENTRIES,
                MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES, MAX_STARTUP_PROVENANCE_BYTES);
    }

    protected PluginRuntimeManager(
            Path pluginsRoot,
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
            BooleanSupplier developmentModeEnabled) {
        this(pluginsRoot, verifierResolver, MAX_STARTUP_VERIFICATION_ENTRIES,
                MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES, MAX_STARTUP_PROVENANCE_BYTES,
                developmentModeEnabled);
    }

    PluginRuntimeManager(Path pluginsRoot,
                         Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                         int maximumStartupVerificationEntries,
                         long maximumStartupVerificationUncompressedBytes) {
        this(pluginsRoot, verifierResolver, maximumStartupVerificationEntries,
                maximumStartupVerificationUncompressedBytes, MAX_STARTUP_PROVENANCE_BYTES);
    }

    PluginRuntimeManager(Path pluginsRoot,
                         Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                         int maximumStartupVerificationEntries,
                         long maximumStartupVerificationUncompressedBytes,
                         long maximumStartupProvenanceBytes) {
        this(pluginsRoot, verifierResolver, maximumStartupVerificationEntries,
                maximumStartupVerificationUncompressedBytes, maximumStartupProvenanceBytes,
                PluginDevelopmentArtifacts::enabled);
    }

    private PluginRuntimeManager(Path pluginsRoot,
                                 Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                 int maximumStartupVerificationEntries,
                                 long maximumStartupVerificationUncompressedBytes,
                                 long maximumStartupProvenanceBytes,
                                 BooleanSupplier developmentModeEnabled) {
        if (pluginsRoot == null) {
            throw new IllegalArgumentException("pluginsRoot must not be null");
        }
        if (maximumStartupVerificationEntries <= 0 || maximumStartupVerificationUncompressedBytes <= 0L
                || maximumStartupProvenanceBytes <= 0L) {
            throw new IllegalArgumentException("startup verification budgets must be positive");
        }
        this.pluginsRoot = pluginsRoot;
        this.layout = new PluginRuntimeLayout(pluginsRoot);
        this.workspaceOwner = new PluginArtifactWorkspaceOwner(layout);
        this.materializer = new PluginArtifactMaterializer(layout);
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.developmentModeEnabled = Objects.requireNonNull(developmentModeEnabled, "developmentModeEnabled");
        this.verificationService = new PluginArtifactVerificationService(
                this.verifierResolver, this.developmentModeEnabled);
        this.provenanceStore = new PluginProvenanceStore(layout);
        this.maximumStartupVerificationEntries = maximumStartupVerificationEntries;
        this.maximumStartupVerificationUncompressedBytes = maximumStartupVerificationUncompressedBytes;
        this.maximumStartupProvenanceBytes = maximumStartupProvenanceBytes;
    }

    /** 由宿主在配置解析后刷新统一验签门面；必须发生在后续 load 原语进入 PF4J 前。 */
    public synchronized void updateVerifier(PluginSupplyChainVerifier verifier) {
        updateVerifierResolver(fixedVerifier(verifier));
    }

    /** 由宿主在配置解析后刷新按来源解析的验签门面；必须发生在后续 load 原语进入 PF4J 前。 */
    public synchronized void updateVerifierResolver(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verificationService = new PluginArtifactVerificationService(
                this.verifierResolver, this.developmentModeEnabled);
    }

    public synchronized void updateAdmissionPolicy(PluginArtifactAdmissionPolicy admissionPolicy) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    /** 启动扫描。每个候选包分别 load/start，单包失败不会阻止其它包。 */
    public synchronized PluginRuntimeStatus start() {
        Path directory = pluginsRoot.toAbsolutePath().normalize();
        resetPluginManager();

        if (PluginDevelopmentArtifacts.enabled()) {
            try {
                beforeProductionScan(directory);
            } catch (IOException | RuntimeException e) {
                return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                        List.of(), List.of(), List.of(new PluginLoadFailure(directory.toString(), describe(e)))));
            }
            return startDevelopmentMode(directory);
        }

        PluginArtifactScanner.ScanResult scan;
        try {
            BasicFileAttributes rootAttributes = attributesIfPresent(directory);
            if (rootAttributes == null) {
                return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.ABSENT,
                        List.of(), List.of(), List.of()));
            }
            if (!rootAttributes.isDirectory()
                    && !rootAttributes.isSymbolicLink() && !rootAttributes.isOther()) {
                return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.ABSENT,
                        List.of(), List.of(), List.of()));
            }
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther()) {
                throw new IOException("plugins root must be a plain directory: " + directory);
            }
            prepareProductionScan(directory);
            workspaceOwner.cleanupAbandoned(packageIndex.isEmpty());
            scan = PluginArtifactScanner.scan(directory);
        } catch (IOException | RuntimeException e) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of(new PluginLoadFailure(directory.toString(), describe(e)))));
        }
        List<Path> candidates = scan.candidates();
        if (candidates.isEmpty()) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of()));
        }

        List<PreparedPluginArtifact> preparedCandidates = new ArrayList<>(candidates.size());
        List<PluginLoadFailure> failures = new ArrayList<>();
        List<PluginRuntimeVerificationSnapshot> verifications = new ArrayList<>(candidates.size());
        PluginStartupResourceBudget startupBudget = new PluginStartupResourceBudget(
                maximumStartupVerificationEntries,
                maximumStartupVerificationUncompressedBytes,
                maximumStartupProvenanceBytes);
        try {
            for (Path candidate : candidates) {
                try {
                    if (startupBudget.verificationExhausted()) {
                        PluginLoadFailure failure = new PluginLoadFailure(candidate.getFileName().toString(),
                                "startup plugin verification cumulative resource budget exceeded");
                        failures.add(failure);
                        log.error("Failed to prepare plugin package {}: {}",
                                candidate.getFileName(), failure.reason());
                        continue;
                    }
                    if (startupBudget.provenanceExhausted()) {
                        PluginLoadFailure failure = new PluginLoadFailure(candidate.getFileName().toString(),
                                "startup plugin provenance sidecar cumulative byte budget exceeded");
                        failures.add(failure);
                        log.error("Failed to prepare plugin package {}: {}",
                                candidate.getFileName(), failure.reason());
                        continue;
                    }
                    Optional<PluginProvenanceStore.MeasuredProvenance> measuredProvenance;
                    try {
                        measuredProvenance = readMeasuredStartupProvenance(
                                candidate, startupBudget.remainingProvenanceBytes());
                    } catch (PluginProvenanceStore.ReadBudgetExceededException failure) {
                        startupBudget.consumeProvenanceFailure(failure.byteCount(), failure);
                        throw failure;
                    } catch (PluginProvenanceStore.InvalidProvenanceException failure) {
                        startupBudget.consumeProvenanceFailure(failure.byteCount(), failure);
                        throw failure;
                    } catch (PluginProvenanceStore.ProvenanceReadException failure) {
                        startupBudget.consumeProvenanceFailure(failure.byteCount(), failure);
                        throw failure;
                    } catch (IOException | RuntimeException failure) {
                        throw failure;
                    }
                    long candidateProvenanceBytes = measuredProvenance
                            .map(PluginProvenanceStore.MeasuredProvenance::byteCount)
                            .orElse(0L);
                    startupBudget.consumeProvenance(candidateProvenanceBytes);
                    PreparedPluginArtifact prepared = prepareProductionArtifact(candidate,
                            measuredProvenance.map(PluginProvenanceStore.MeasuredProvenance::record),
                            startupBudget, verifications);
                    preparedCandidates.add(prepared);
                } catch (IOException | RuntimeException e) {
                    PluginLoadFailure failure = new PluginLoadFailure(
                            candidate.getFileName().toString(), describe(e));
                    failures.add(failure);
                    log.error("Failed to prepare plugin package {}: {}", candidate.getFileName(), failure.reason());
                }
            }
            PluginArtifactLoadPlan loadPlan = PluginArtifactLoadPlan.createInspected(preparedCandidates.stream()
                    .map(PreparedPluginArtifact::loadPlanEntry)
                    .toList());
            failures.addAll(loadPlan.failures());
            for (PluginLoadFailure failure : loadPlan.failures()) {
                log.error("Failed to prepare plugin package {}: {}", failure.source(), failure.reason());
            }
            Map<Path, PreparedPluginArtifact> preparedByPath = new LinkedHashMap<>();
            for (PreparedPluginArtifact prepared : preparedCandidates) {
                preparedByPath.put(prepared.originalArtifact(), prepared);
            }
            Set<String> failedPluginIds = new LinkedHashSet<>(loadPlan.skippedPluginIds());
            for (PluginArtifactLoadPlan.Entry candidate : loadPlan.orderedEntries()) {
                Optional<PluginLoadFailure> blocked =
                        loadPlan.blockedByFailedRequiredDependency(candidate, failedPluginIds);
                if (blocked.isPresent()) {
                    failures.add(blocked.get());
                    failedPluginIds.add(candidate.pluginId());
                    log.error("Skipped plugin package {}: {}",
                            blocked.get().source(), blocked.get().reason());
                    continue;
                }
                PreparedPluginArtifact prepared = preparedByPath.get(
                        candidate.artifactPath().toAbsolutePath().normalize());
                if (prepared == null) {
                    throw new IllegalStateException("prepared plugin artifact disappeared from load plan: "
                            + candidate.artifactPath());
                }
                try {
                    loadPreparedProductionArtifact(prepared);
                } catch (RuntimeException e) {
                    failedPluginIds.add(candidate.pluginId());
                    failures.add(new PluginLoadFailure(
                            candidate.artifactPath().getFileName().toString(), describe(e)));
                    log.error("Failed to load plugin package {}: {}",
                            candidate.artifactPath().getFileName(), describe(e));
                }
            }
        } finally {
            preparedCandidates.forEach(PreparedPluginArtifact::close);
        }
        for (String packageId : packageIndex.packageIds()) {
            try {
                startPlugin(packageId);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(packageId, describe(e)));
                log.error("Failed to start plugin package {}: {}", packageId, describe(e));
            }
        }
        return cache(PluginRuntimeStatus.populated(directory, phaseSnapshot(), failures, verifications));
    }

    /** 从明确路径加载一个插件包并创建新 generation；不会启动插件入口。 */
    public synchronized LoadedPluginPackage loadPlugin(Path artifactPath) {
        try {
            beforeProductionScan(pluginsRoot);
        } catch (IOException e) {
            throw new PluginRuntimeOperationException(
                    "plugin directory is not safe for an artifact load", e);
        }
        if (PluginDevelopmentArtifacts.enabled() && artifactPath != null && Files.isDirectory(artifactPath)) {
            return loadDevelopmentPlugin(artifactPath);
        }
        try {
            workspaceOwner.secureLoadingRoots();
            workspaceOwner.cleanupAbandoned(packageIndex.isEmpty());
        } catch (IOException | RuntimeException e) {
            throw new PluginRuntimeOperationException(
                    "plugin directory is not safe for a production artifact load", e);
        }
        PreparedPluginArtifact prepared = prepareProductionArtifact(artifactPath);
        try {
            return loadPreparedProductionArtifact(prepared);
        } finally {
            prepared.close();
        }
    }

    private LoadedPluginPackage loadDevelopmentPlugin(Path classesDirectory) {
        Path normalizedClasses = classesDirectory.toAbsolutePath().normalize();
        PluginDevelopmentArtifacts.DevelopmentDiscovery discovery =
                PluginDevelopmentArtifacts.discover(pluginsRoot);
        PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact = discovery.artifacts().stream()
                .filter(candidate -> candidate.classesDirectory().equals(normalizedClasses))
                .findFirst()
                .orElseThrow(() -> new PluginRuntimeOperationException(
                        "development plugin artifact not found: " + normalizedClasses));
        PluginDescriptor descriptor = PluginPackageReader.inspectDescriptor(artifact.descriptorPath());
        if (packageIndex.contains(descriptor.id())) {
            throw new PluginRuntimeOperationException("plugin package already loaded: " + descriptor.id());
        }
        PluginDevelopmentArtifacts.DevelopmentCacheSession session =
                ensureDevelopmentCacheSession(discovery.cacheRoot());
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materialized =
                PluginDevelopmentArtifacts.materialize(artifact, session);
        return loadPreparedPlugin(materialized.classesDirectory(), materialized.pf4jLoadPath(),
                session.sessionRoot(), materialized.descriptor(), null);
    }

    private PluginRuntimeStatus startDevelopmentMode(Path productionDirectory) {
        PluginDevelopmentArtifacts.DevelopmentDiscovery discovery;
        try {
            discovery = PluginDevelopmentArtifacts.discover(pluginsRoot);
        } catch (RuntimeException e) {
            return cache(new PluginRuntimeStatus(productionDirectory, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of(new PluginLoadFailure(
                    PluginDevelopmentArtifacts.ROOT_PROPERTY, describe(e)))));
        }
        PluginDevelopmentDiagnostics.printBanner(productionDirectory, discovery);
        Path developmentRoot = discovery.developmentRoot();
        if (!Files.isDirectory(developmentRoot)) {
            return cache(new PluginRuntimeStatus(developmentRoot, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of()));
        }
        List<PluginLoadFailure> failures = new ArrayList<>(PluginDevelopmentDiagnostics.sourceFailures(discovery));
        if (discovery.artifacts().isEmpty()) {
            return cache(new PluginRuntimeStatus(developmentRoot, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), failures));
        }

        PluginDevelopmentArtifacts.DevelopmentCacheSession session;
        try {
            session = ensureDevelopmentCacheSession(discovery.cacheRoot());
        } catch (RuntimeException e) {
            failures.add(new PluginLoadFailure(discovery.cacheRoot().toString(), describe(e)));
            log.error("Failed to open plugin development cache session {}", discovery.cacheRoot(), e);
            return cache(PluginRuntimeStatus.populated(developmentRoot, phaseSnapshot(), failures));
        }
        List<PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin> materializedPlugins = new ArrayList<>();
        for (PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact : discovery.artifacts()) {
            try {
                materializedPlugins.add(PluginDevelopmentArtifacts.materialize(artifact, session));
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(artifact.moduleRoot().getFileName().toString(), describe(e)));
                log.error("Failed to materialize development plugin module {}",
                        artifact.moduleRoot().getFileName(), e);
            }
        }
        for (PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materialized
                : PluginDevelopmentArtifacts.dependencyOrder(materializedPlugins)) {
            try {
                loadPreparedPlugin(materialized.classesDirectory(), materialized.pf4jLoadPath(),
                        session.sessionRoot(), materialized.descriptor(), null);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(materialized.descriptor().id(), describe(e)));
                log.error("Failed to load development plugin module {}",
                        materialized.moduleRoot().getFileName(), e);
            }
        }
        for (String packageId : packageIndex.packageIds()) {
            try {
                startPlugin(packageId);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(packageId, describe(e)));
                log.error("Failed to start plugin package {}", packageId, e);
            }
        }
        return cache(PluginRuntimeStatus.populated(developmentRoot, phaseSnapshot(), failures));
    }

    private LoadedPluginPackage loadPreparedPlugin(Path artifactPath, Path pf4jLoadPath, Path pluginManagerRoot,
                                                    PluginDescriptor packageDescriptor,
                                                    PluginArtifactSnapshot productionSnapshot) {
        if (packageIndex.contains(packageDescriptor.id())) {
            workspaceOwner.discard(productionSnapshot);
            throw new PluginRuntimeOperationException("plugin package already loaded: " + packageDescriptor.id());
        }
        try {
            ensureManager(pluginManagerRoot);
        } catch (Throwable failure) {
            workspaceOwner.discard(productionSnapshot);
            throw operationFailure("failed to initialize plugin runtime before loading " + artifactPath, failure);
        }
        Set<String> wrappersBeforeLoad;
        try {
            wrappersBeforeLoad = loadedWrapperIds();
        } catch (Throwable failure) {
            workspaceOwner.discard(productionSnapshot);
            throw operationFailure("failed to inspect plugin runtime before loading " + artifactPath, failure);
        }
        String packageId;
        try {
            if (productionSnapshot != null) {
                productionSnapshot.verifyLoadPath(pf4jLoadPath);
            }
            packageId = pluginManager.loadPlugin(pf4jLoadPath);
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to load plugin artifact " + artifactPath, failure);
        }
        if (packageId == null || packageId.isBlank()) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J returned no package id for " + artifactPath);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        if (packageIndex.contains(packageId)) {
            // 不得在重复加载分支调用 unloadPlugin：PF4J 返回的 id 可能指向原有 wrapper，
            // 此时卸载会错误释放仍在服务的旧 generation。
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "plugin package already loaded: " + packageId);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        PluginWrapper wrapper;
        try {
            wrapper = pluginManager.getPlugin(packageId);
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to inspect loaded plugin package " + packageId, failure);
        }
        if (wrapper == null) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not retain loaded package: " + packageId);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        String version;
        try {
            version = wrapper.getDescriptor().getVersion();
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to inspect loaded plugin descriptor " + packageId, failure);
        }
        Entry entry = packageIndex.add(
                packageId,
                artifactPath,
                pf4jLoadPath,
                version,
                PluginRuntimePackagePhase.LOADED,
                packageDescriptor,
                productionSnapshot
        );
        try {
            LoadedPluginPackage loaded = snapshot(entry, true);
            entry.updateDescriptor(validateReleaseShape(loaded));
            refreshStatus();
            return loaded;
        } catch (Throwable failure) {
            boolean released = false;
            try {
                released = pluginManager.unloadPlugin(packageId);
            } catch (Throwable cleanupFailure) {
                addSuppressedSafely(failure, cleanupFailure);
            }
            try {
                if (pluginManager.getPlugin(packageId) == null) {
                    Entry removed = packageIndex.remove(packageId);
                    if (released) {
                        releaseProductionSnapshot(removed);
                    } else {
                        retainUnconfirmedProductionSnapshot(removed);
                    }
                }
            } catch (Throwable inspectionFailure) {
                addSuppressedSafely(failure, inspectionFailure);
            }
            refreshStatusSafely(failure);
            throw operationFailure("failed to validate loaded plugin package " + packageId, failure);
        }
    }

    /** 启动 PF4J 插件入口，并返回 load 准入时固化的本代功能插件与 Spring 模块快照。 */
    public synchronized LoadedPluginPackage startPlugin(String packageId) {
        Entry entry = requireEntry(packageId);
        if (entry.phase() == PluginRuntimePackagePhase.STARTED) {
            try {
                return snapshot(entry, true);
            } catch (Throwable failure) {
                throw operationFailure("failed to inspect started plugin package " + packageId, failure);
            }
        }
        PluginRuntimePackagePhase previousPhase = entry.phase();
        PluginState result;
        try {
            result = pluginManager.startPlugin(packageId);
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, previousPhase, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to start plugin package " + packageId, failure);
        }
        if (result != PluginState.STARTED) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not start plugin package " + packageId + " (state=" + result + ")");
            reconcileEntryWithWrapper(entry, previousPhase, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        entry.updatePhase(PluginRuntimePackagePhase.STARTED);
        refreshStatus();
        try {
            return snapshot(entry, true);
        } catch (Throwable failure) {
            throw operationFailure("failed to inspect started plugin package " + packageId, failure);
        }
    }

    /** 停止 PF4J 插件入口但保留 wrapper/classloader。 */
    public synchronized LoadedPluginPackage stopPlugin(String packageId) {
        Entry entry = requireEntry(packageId);
        if (entry.phase() != PluginRuntimePackagePhase.STARTED) {
            try {
                return snapshot(entry, false);
            } catch (Throwable failure) {
                throw operationFailure("failed to inspect stopped plugin package " + packageId, failure);
            }
        }
        PluginState result;
        try {
            result = pluginManager.stopPlugin(packageId);
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to stop plugin package " + packageId, failure);
        }
        if (result == PluginState.STARTED) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J left plugin package started: " + packageId);
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        entry.updatePhase(PluginRuntimePackagePhase.STOPPED);
        refreshStatus();
        return snapshot(entry, false);
    }

    /**
     * 物理卸载并关闭 classloader。存在已加载的非可选反向依赖时拒绝，避免 PF4J 隐式级联卸载。
     */
    public synchronized UnloadedPluginPackage unloadPlugin(String packageId) {
        Entry entry = requireEntry(packageId);
        List<String> dependents = activeDependents(packageId);
        if (!dependents.isEmpty()) {
            throw new PluginRuntimeOperationException("plugin package " + packageId
                    + " is required by loaded package(s): " + String.join(", ", dependents));
        }
        if (entry.phase() == PluginRuntimePackagePhase.STARTED) {
            stopPlugin(packageId);
        }
        boolean unloaded;
        try {
            unloaded = pluginManager.unloadPlugin(packageId);
        } catch (Throwable failure) {
            // 某些 PF4J 实现会先移除 wrapper、再在关闭 classloader 时抛异常。此时旧句柄已经
            // 不可恢复，必须同步删除本地 entry；调用方仍会收到失败并据此报告 JAR 未确认可替换。
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to unload plugin package " + packageId, failure);
        }
        boolean wrapperPresent;
        try {
            wrapperPresent = pluginManager.getPlugin(packageId) != null;
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to verify unloaded plugin package " + packageId, failure);
        }
        if (!unloaded || wrapperPresent) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not unload plugin package " + packageId);
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        Entry removed = packageIndex.remove(packageId);
        releaseProductionSnapshot(removed);
        refreshStatus();
        return new UnloadedPluginPackage(
                entry.packageId(), entry.artifactPath(), entry.version(), entry.generation());
    }

    /** 当前已加载包的纯值阶段快照。 */
    public synchronized Map<String, PluginRuntimePackagePhase> packagePhases() {
        return packageIndex.packagePhases();
    }

    private Map<String, PluginRuntimePackagePhase> phaseSnapshot() {
        return packageIndex.packagePhases();
    }

    public synchronized Optional<Long> generation(String packageId) {
        return packageIndex.generation(packageId);
    }

    public synchronized Optional<Path> artifactPath(String packageId) {
        return packageIndex.artifactPath(packageId);
    }

    /** 当前已加载 generation 是否来自显式插件开发模式。 */
    public synchronized boolean isDevelopmentArtifact(String packageId) {
        return packageIndex.isDevelopmentArtifact(packageId);
    }

    /** 当前已加载 generation 的纯值描述符；停止服务不移除，物理卸载时随 runtime entry 一并释放。 */
    public synchronized Optional<PluginDescriptor> loadedDescriptor(String packageId) {
        return packageIndex.descriptor(packageId);
    }

    /** 全部已加载 generation 的纯值描述符快照，包含 LOADED / STARTED / STOPPED。 */
    public synchronized Map<String, PluginDescriptor> loadedDescriptors() {
        return packageIndex.descriptors();
    }

    /** 当前已加载的非可选反向依赖包。 */
    public synchronized List<String> activeDependents(String packageId) {
        if (pluginManager == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            if (wrapper.getPluginId().equals(packageId)) {
                continue;
            }
            for (PluginDependency dependency : wrapper.getDescriptor().getDependencies()) {
                if (!dependency.isOptional() && packageId.equals(dependency.getPluginId())) {
                    result.add(wrapper.getPluginId());
                }
            }
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    public Optional<PluginRuntimeStatus> status() {
        return Optional.ofNullable(status);
    }

    /** 仅供运行时包内测试做故障注入；生产调用方不得取得物理 manager。 */
    Optional<PluginManager> pluginManagerForTest() {
        return Optional.ofNullable(pluginManager);
    }

    /** 是否已初始化物理插件运行时。只暴露宿主诊断状态，不暴露 PF4J 控制面。 */
    public synchronized boolean isPhysicalRuntimeInitialized() {
        return pluginManager != null;
    }

    /** 汇总当前 STARTED generation 在 load 准入时固化的 provider 快照，不重新调用插件 getter。 */
    public synchronized PluginInventory inspectPlugins() {
        if (pluginManager == null) {
            return PluginInventory.empty();
        }
        List<PluginInstallation> installations = new ArrayList<>();
        List<PluginContextModule> contextModules = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (Entry entry : packageIndex.entries()) {
            if (entry.phase() != PluginRuntimePackagePhase.STARTED) {
                continue;
            }
            PluginInventory captured = contributionSnapshot(entry);
            installations.addAll(captured.installations());
            contextModules.addAll(captured.contextModules());
            failures.addAll(captured.failures());
        }
        return new PluginInventory(installations, contextModules, failures);
    }

    public synchronized PluginDiscoveryResult discoverFeaturePlugins() {
        return toDiscoveryResult(inspectPlugins());
    }

    /**
     * 从既有 inventory 投影发现结果（不重新清点 / 不再次调用 provider），仅把当前 generation 盖到每条 discovered 上。
     * 供 bootstrap 会话在启动期同一次清点内同时产出 inventory 与 discovery，避免对 provider 重复调用。
     */
    public synchronized PluginDiscoveryResult toDiscoveryResult(PluginInventory inventory) {
        PluginInventory source = inventory == null ? PluginInventory.empty() : inventory;
        PluginDiscoveryResult raw = source.toDiscoveryResult();
        List<DiscoveredFeaturePlugin> discovered = raw.discovered().stream()
                .map(item -> new DiscoveredFeaturePlugin(item.sourcePluginId(),
                        item.featurePluginId(), generation(item.sourcePluginId()).orElse(0L),
                        item.plugin(), item.classLoader()))
                .toList();
        return new PluginDiscoveryResult(discovered, raw.failures());
    }

    public synchronized List<PluginContextModule> inspectContextModules() {
        return inspectPlugins().contextModules();
    }

    /** 当前所有 STARTED 包的代际快照。 */
    public synchronized List<LoadedPluginPackage> startedPackages() {
        return packageIndex.entries().stream()
                .filter(entry -> entry.phase() == PluginRuntimePackagePhase.STARTED)
                .sorted(Comparator.comparing(Entry::packageId))
                .map(entry -> snapshot(entry, true))
                .toList();
    }

    /**
     * 进程级关闭：停止全部已启动插件、卸载全部插件、释放 PF4J classloader / 文件句柄、清空内部 entry / status / generation
     * 引用。多次调用安全（幂等）；批量 stop / unload 各自 best-effort——任一抛错只记日志、不影响另一批清退，不致核心退出失败。
     * 供唯一 bootstrap session 在进程最终退出（PROCESS）或 context 销毁（CONTEXT）时统一关闭运行时；禁止 app 侧经
     * 直接操作 PF4J。不抛异常、不吞掉 JVM 致命 Error。
     */
    public synchronized void shutdown() {
        PluginManager previous = pluginManager;
        PluginDevelopmentArtifacts.DevelopmentCacheSession previousDevelopmentSession = developmentCacheSession;
        if (previous == null && packageIndex.isEmpty() && previousDevelopmentSession == null
                && !workspaceOwner.hasUnconfirmedSnapshots()) {
            // 已关闭（或从未扫描）：清空残余引用即返回，幂等。
            packageIndex.clearGenerations();
            status = null;
            return;
        }
        List<Entry> previousEntries = packageIndex.clearAll();
        pluginManager = null;
        developmentCacheSession = null;
        status = null;
        boolean released = previous == null || bestEffortStopAndUnload(previous, "shutdown");
        workspaceOwner.closeAll(productionSnapshots(previousEntries), released, "shutdown");
        closeDevelopmentCacheSession(previousDevelopmentSession, released, "shutdown");
    }

    public Path pluginsRoot() {
        return pluginsRoot;
    }

    private LoadedPluginPackage snapshot(Entry entry, boolean includeContributions) {
        PluginInventory inventory = PluginInventory.empty();
        List<PluginContextModule> modules = List.of();
        if (includeContributions && pluginManager != null) {
            inventory = contributionSnapshot(entry);
            modules = inventory.contextModules();
        }
        return new LoadedPluginPackage(
                entry.packageId(), entry.artifactPath(), entry.version(), entry.generation(),
                entry.phase(), inventory, modules);
    }

    /** bootstrap 子类可在扫描或任意单包加载（含开发目录）触碰 entry 前取得并复核跨进程目录租约。 */
    protected void beforeProductionScan(Path directory) throws IOException {
        // 默认 manager 没有 bootstrap 会话所有权；生产会话子类覆写。
    }

    private void prepareProductionScan(Path directory) throws IOException {
        beforeProductionScan(directory);
        workspaceOwner.secureLoadingRoots();
    }

    /**
     * 每个物理 generation 恰好读取一次 provider 的 feature/configuration 声明并固化为宿主快照。
     * load、start、Spring 接入与状态查询只复用该快照，禁止状态化 getter 改变已验证身份或制造半份装配。
     */
    private PluginInventory contributionSnapshot(Entry entry) {
        if (entry.contributionSnapshot() == null) {
            PixivPluginDiscoveryBridge bridge = new PixivPluginDiscoveryBridge();
            entry.updateContributionSnapshot(attachPackageMetadata(
                    bridge.inspectLoadedPackage(pluginManager, entry.packageId())));
        }
        return entry.contributionSnapshot();
    }

    /**
     * 发现桥接从运行期插件实例重建功能元数据；包级替代关系与生命周期策略只存在于清单，
     * 因此按当前 runtime entry 重新附着，确保 load/start 后仍保留已验签的包元数据。
     */
    private PluginInventory attachPackageMetadata(PluginInventory inventory) {
        List<PluginInstallation> installations = inventory.installations().stream()
                .map(installation -> {
                    Entry entry = packageIndex.get(installation.descriptor().sourcePluginId());
                    if (entry == null) {
                        return installation;
                    }
                    PluginDescriptor descriptor = installation.descriptor()
                            .withPackageMetadataFrom(entry.descriptor());
                    return new PluginInstallation(descriptor, installation.status(), installation.classLoader(),
                            installation.plugin());
                })
                .toList();
        return new PluginInventory(installations, inventory.contextModules(), inventory.failures());
    }

    /** 当前发布格式要求物理包与唯一功能插件同 id，并至多声明一个 Spring 模块。 */
    private static PluginDescriptor validateReleaseShape(LoadedPluginPackage loaded) {
        List<PluginInstallation> registrable = loaded.inventory().installations().stream()
                .filter(PluginInstallation::registrable)
                .toList();
        if (registrable.size() != 1) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " must expose exactly one registrable feature plugin");
        }
        PluginDescriptor featureDescriptor = registrable.get(0).descriptor();
        if (!loaded.packageId().equals(featureDescriptor.id())) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " must contribute a feature plugin with the same id");
        }
        if (loaded.contextModules().size() > 1) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " declared multiple context modules");
        }
        if (!loaded.contextModules().isEmpty()
                && !loaded.packageId().equals(loaded.contextModules().get(0).sourcePluginId())) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " declared a context module for another package");
        }
        return featureDescriptor;
    }

    private void ensureManager() {
        ensureManager(pluginsRoot);
    }

    private void ensureManager(Path root) {
        if (pluginManager == null) {
            pluginManager = new HostControlledPluginManager(root);
        }
    }

    private PreparedPluginArtifact prepareProductionArtifact(Path artifactPath) {
        Path attemptedPath = Objects.requireNonNull(artifactPath, "artifactPath")
                .toAbsolutePath().normalize();
        List<PluginRuntimeVerificationSnapshot> latestVerification = new ArrayList<>(1);
        try {
            return prepareProductionArtifact(
                    attemptedPath, provenanceStore.read(attemptedPath), null, latestVerification);
        } finally {
            retainLatestRuntimeVerifications(attemptedPath, latestVerification);
        }
    }

    private PreparedPluginArtifact prepareProductionArtifact(
            Path artifactPath,
            Optional<PluginProvenanceRecord> preReadProvenance,
            PluginStartupResourceBudget startupBudget,
            List<PluginRuntimeVerificationSnapshot> startupVerifications) {
        PluginPackageLimits verificationLimits = startupBudget == null
                ? PRODUCTION_PACKAGE_LIMITS
                : startupBudget.remainingVerificationLimits(PRODUCTION_PACKAGE_LIMITS);
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, artifactPath, PRODUCTION_PACKAGE_LIMITS.maxArchiveBytes());
        try {
            Path frozenArtifact = snapshot.snapshotArtifact();
            PluginPackageVerifier.VerificationUsage verificationUsage;
            try {
                verificationUsage = verifyAndMeasureProductionPackage(frozenArtifact, verificationLimits);
            } catch (PluginPackageException failure) {
                if (startupBudget != null) {
                    startupBudget.consumeVerificationFailure(failure);
                }
                throw failure;
            }
            if (startupBudget != null) {
                startupBudget.consumeVerification(verificationUsage);
            }
            PluginPackageInspection inspection = PluginPackageReader.inspect(
                    frozenArtifact, PRODUCTION_PACKAGE_LIMITS);
            if (inspection.innerJarEntry() != null) {
                throw new PluginRuntimeOperationException(
                        "installed plugin package must be canonical and cannot contain an inner plugin jar: "
                                + snapshot.originalArtifact());
            }
            PluginProvenanceRecord provenance = Objects.requireNonNull(
                    preReadProvenance, "preReadProvenance").orElse(null);
            VerificationResult result = verificationService.verifyInstalled(
                    frozenArtifact, inspection.descriptor(), provenance);
            if (startupVerifications != null) {
                startupVerifications.add(new PluginRuntimeVerificationSnapshot(
                        snapshot.originalArtifact(),
                        inspection.descriptor().id(),
                        inspection.descriptor().version(),
                        result.sizeBytes(),
                        result.sha256(),
                        provenance,
                        result));
            }
            try {
                if (provenance != null) {
                    persistOfflineVerification(snapshot.originalArtifact(), provenance.withOfflineResult(
                            result, inspection.descriptor().id(), inspection.descriptor().version()));
                }
            } catch (IOException e) {
                log.warn("Failed to persist plugin verification provenance for {}: {}",
                        snapshot.originalArtifact().getFileName(), e.toString());
            }
            if (!result.accepted()) {
                throw new PluginRuntimeOperationException(
                        "plugin verification failed before load: " + result.status());
            }
            if (provenance != null && provenance.repositoryId() != null) {
                PluginArtifactAdmissionResult admission = admissionPolicy.evaluate(
                        new PluginArtifactAdmissionRequest(provenance.repositoryId(), inspection.descriptor().id(),
                                inspection.descriptor().version(), result.sha256(), result.keyId(), result.publisher()));
                if (admission == null || !admission.allowed()) {
                    throw new PluginRuntimeOperationException("plugin admission rejected before load: "
                            + (admission != null ? admission.code() + ": " + admission.detail() : "null result"));
                }
                if (admission.warning()) {
                    log.warn("Plugin admission warning before load for {}: {}: {}",
                            inspection.descriptor().id(), admission.code(), admission.detail());
                }
            }
            requireExecutionAdmission(inspection.descriptor(), provenance, result);
            return new PreparedPluginArtifact(snapshot, inspection, result.sha256());
        } catch (Throwable failure) {
            snapshot.close();
            rethrowFatal(failure);
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new PluginRuntimeOperationException(
                    "failed to prepare plugin artifact " + snapshot.originalArtifact(), failure);
        }
    }

    private void requireExecutionAdmission(
            PluginDescriptor descriptor,
            PluginProvenanceRecord provenance,
            VerificationResult result) {
        if (descriptor.executionMode() == PluginExecutionMode.ISOLATED_PROCESS) {
            throw new PluginRuntimeOperationException(
                    "isolated-process plugin execution is unavailable until the bounded worker protocol is active: "
                            + descriptor.id());
        }
        if (descriptor.lifecyclePolicy() != PluginLifecyclePolicy.PROCESS_RESTART) {
            throw new PluginRuntimeOperationException(
                    "trusted in-process plugin must use process-restart lifecycle: " + descriptor.id());
        }
        boolean explicitDevelopmentAdmission = provenance != null
                && provenance.developmentOnly()
                && developmentModeEnabled.getAsBoolean();
        boolean officialAdmission = provenance != null
                && result.status() == VerificationStatus.VERIFIED
                && (provenance.officialRepository()
                || provenance.source() == PluginPackageSource.LOCAL_UPLOAD && provenance.signature() != null);
        if (!explicitDevelopmentAdmission && !officialAdmission) {
            throw new PluginRuntimeOperationException(
                    "trusted in-process execution requires an official verified signature: " + descriptor.id());
        }
    }

    Optional<PluginProvenanceStore.MeasuredProvenance> readMeasuredStartupProvenance(
            Path artifactPath,
            long maximumBytes) throws IOException {
        return provenanceStore.readMeasuredCompatible(artifactPath, maximumBytes);
    }

    void persistOfflineVerification(Path artifactPath, PluginProvenanceRecord provenance) throws IOException {
        provenanceStore.write(artifactPath, provenance);
    }

    PluginPackageVerifier.VerificationUsage verifyAndMeasureProductionPackage(
            Path frozenArtifact,
            PluginPackageLimits limits) {
        return PluginPackageVerifier.verifyAndMeasure(frozenArtifact, limits);
    }

    private LoadedPluginPackage loadPreparedProductionArtifact(PreparedPluginArtifact prepared) {
        if (packageIndex.contains(prepared.inspection().descriptor().id())) {
            throw new PluginRuntimeOperationException(
                    "plugin package already loaded: " + prepared.inspection().descriptor().id());
        }
        PluginArtifactMaterializer.MaterializedPluginArtifact materialized = materializer.materialize(
                prepared.snapshot(), prepared.inspection(), prepared.verifiedSha256());
        PluginArtifactSnapshot ownedSnapshot = prepared.detachSnapshot();
        return loadPreparedPlugin(materialized.originalArtifactPath(), materialized.pf4jLoadPath(), pluginsRoot,
                prepared.inspection().descriptor(), ownedSnapshot);
    }

    private Entry requireEntry(String packageId) {
        Entry entry = packageIndex.get(packageId);
        if (entry == null || pluginManager == null) {
            throw new PluginRuntimeOperationException("plugin package is not loaded: " + packageId);
        }
        return entry;
    }

    /**
     * 运行期 load / reload 也会重新执行离线复验；其结构化结果必须替换同一安装路径的启动快照。
     * 这样即使 sidecar 写回失败，管理面也不会把旧启动结论当成最新验证事实。
     */
    private void retainLatestRuntimeVerifications(
            Path attemptedPath,
            List<PluginRuntimeVerificationSnapshot> latest) {
        PluginRuntimeStatus current = status;
        if (current == null) {
            if (latest == null || latest.isEmpty()) {
                return;
            }
            current = PluginRuntimeStatus.populated(
                    pluginsRoot.toAbsolutePath().normalize(), phaseSnapshot(), List.of());
        }
        status = current.withLatestRuntimeVerifications(
                attemptedPath, latest, MAX_STARTUP_VERIFICATION_ENTRIES);
    }

    private void refreshStatus() {
        if (status == null && packageIndex.isEmpty()) {
            return;
        }
        PluginRuntimeStatus current = status == null
                ? PluginRuntimeStatus.populated(
                        pluginsRoot.toAbsolutePath().normalize(), phaseSnapshot(), List.of())
                : status;
        status = current.refreshed(phaseSnapshot());
    }

    private synchronized void resetPluginManager() {
        PluginManager previous = pluginManager;
        PluginDevelopmentArtifacts.DevelopmentCacheSession previousDevelopmentSession = developmentCacheSession;
        List<Entry> previousEntries = packageIndex.clearEntries();
        pluginManager = null;
        developmentCacheSession = null;
        boolean released = previous == null || bestEffortStopAndUnload(previous, "reset");
        workspaceOwner.closeAll(productionSnapshots(previousEntries), released, "reset");
        closeDevelopmentCacheSession(previousDevelopmentSession, released, "reset");
    }

    private PluginRuntimeStatus cache(PluginRuntimeStatus value) {
        this.status = value;
        return value;
    }

    private static boolean bestEffortStopAndUnload(PluginManager manager, String action) {
        Throwable fatal = null;
        boolean releasedCleanly = true;
        try {
            manager.stopPlugins();
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                fatal = failure;
            } else {
                releasedCleanly = false;
                log.warn("Error stopping plugins during runtime {}: {}", action, describe(failure));
            }
        }
        try {
            manager.unloadPlugins();
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                if (fatal == null) {
                    fatal = failure;
                } else {
                    addSuppressedSafely(fatal, failure);
                }
            } else {
                releasedCleanly = false;
                log.warn("Error unloading plugins during runtime {}: {}", action, describe(failure));
            }
        }
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        try {
            if (!manager.getPlugins().isEmpty()) {
                releasedCleanly = false;
                log.warn("Plugin wrappers remain after runtime {}; owned artifact workspaces will be retained",
                        action);
            }
        } catch (Throwable failure) {
            rethrowFatal(failure);
            releasedCleanly = false;
            log.warn("Failed to verify plugin wrapper release during runtime {}: {}", action, describe(failure));
        }
        return releasedCleanly;
    }

    private PluginDevelopmentArtifacts.DevelopmentCacheSession ensureDevelopmentCacheSession(Path cacheRoot) {
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        PluginDevelopmentArtifacts.DevelopmentCacheSession current = developmentCacheSession;
        if (current != null) {
            if (!current.cacheRoot().equals(normalizedCacheRoot)) {
                throw new PluginRuntimeOperationException("plugin development cache root changed while active: "
                        + current.cacheRoot() + " -> " + normalizedCacheRoot);
            }
            return current;
        }
        if (pluginManager != null) {
            throw new PluginRuntimeOperationException(
                    "cannot open plugin development cache session after PF4J manager initialization");
        }
        PluginDevelopmentArtifacts.DevelopmentCacheSession opened =
                PluginDevelopmentArtifacts.openSession(normalizedCacheRoot);
        developmentCacheSession = opened;
        return opened;
    }

    private static void closeDevelopmentCacheSession(
            PluginDevelopmentArtifacts.DevelopmentCacheSession session, boolean runtimeReleased, String action) {
        if (session == null) {
            return;
        }
        if (!runtimeReleased) {
            log.warn("Retaining plugin development cache session {} because runtime {} did not release cleanly",
                    session.sessionRoot(), action);
            return;
        }
        try {
            session.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean plugin development cache session {} during {}: {}",
                    session.sessionRoot(), action, e.toString());
        }
    }

    private void releaseProductionSnapshot(Entry removedEntry) {
        if (removedEntry == null) {
            return;
        }
        // 一个失败 load 可能留下多个 wrapper；它们共享同一 snapshot，只有最后一个 entry 移除后才关闭。
        workspaceOwner.release(
                removedEntry.productionSnapshot(), packageIndex.productionSnapshots());
    }

    private void retainUnconfirmedProductionSnapshot(Entry removedEntry) {
        if (removedEntry != null) {
            workspaceOwner.retainUnconfirmed(removedEntry.productionSnapshot());
        }
    }

    private void retainUnconfirmedProductionSnapshot(PluginArtifactSnapshot snapshot) {
        workspaceOwner.retainUnconfirmed(snapshot);
    }

    private static List<PluginArtifactSnapshot> productionSnapshots(List<Entry> runtimeEntries) {
        return runtimeEntries.stream().map(Entry::productionSnapshot).toList();
    }

    private Set<String> loadedWrapperIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            ids.add(wrapper.getPluginId());
        }
        return ids;
    }

    /** load 原语抛错时只清理由本次调用新增的 wrapper，绝不触碰先前在场的同 id 代际。 */
    private void cleanupNewWrappers(
            Set<String> wrappersBeforeLoad,
            Throwable primaryFailure,
            Path artifactPath,
            Path pf4jLoadPath,
            PluginDescriptor packageDescriptor,
            PluginArtifactSnapshot productionSnapshot) {
        Set<String> current;
        try {
            current = loadedWrapperIds();
        } catch (Throwable inspectionFailure) {
            addSuppressedSafely(primaryFailure, inspectionFailure);
            retainUnconfirmedProductionSnapshot(productionSnapshot);
            return;
        }
        current.removeAll(wrappersBeforeLoad);
        boolean observedNewWrapper = !current.isEmpty();
        boolean workspaceMayStillBeReferenced = false;
        for (String pluginId : current) {
            boolean unloaded = false;
            try {
                unloaded = pluginManager.unloadPlugin(pluginId);
            } catch (Throwable cleanupFailure) {
                addSuppressedSafely(primaryFailure, cleanupFailure);
            }
            PluginWrapper remaining = null;
            boolean remainingInspected = false;
            try {
                remaining = pluginManager.getPlugin(pluginId);
                remainingInspected = true;
            } catch (Throwable inspectionFailure) {
                addSuppressedSafely(primaryFailure, inspectionFailure);
                workspaceMayStillBeReferenced = true;
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
            if (!unloaded || remaining != null) {
                addSuppressedSafely(primaryFailure, new PluginRuntimeOperationException(
                        "PF4J retained wrapper after failed load cleanup: " + pluginId));
            }
            if (remaining != null) {
                workspaceMayStillBeReferenced = true;
                retainResidualWrapper(pluginId, remaining, artifactPath, pf4jLoadPath, packageDescriptor,
                        productionSnapshot, primaryFailure);
            } else if (remainingInspected && !unloaded) {
                // wrapper 已从 manager 消失但 unload 未确认成功，无法证明 classloader 已释放。
                workspaceMayStillBeReferenced = true;
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
        }
        if (!workspaceMayStillBeReferenced) {
            if (observedNewWrapper) {
                workspaceOwner.discard(productionSnapshot);
            } else {
                // PF4J 可在创建 classloader 后、注册 wrapper 前抛错；看不到新增 wrapper 不能证明句柄已释放。
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
        }
        refreshStatusSafely(primaryFailure);
    }

    private void retainResidualWrapper(
            String pluginId,
            PluginWrapper wrapper,
            Path artifactPath,
            Path pf4jLoadPath,
            PluginDescriptor packageDescriptor,
            PluginArtifactSnapshot productionSnapshot,
            Throwable primaryFailure) {
        if (packageIndex.contains(pluginId)) {
            retainUnconfirmedProductionSnapshot(productionSnapshot);
            return;
        }
        try {
            PluginRuntimePackagePhase phase = wrapper.getPluginState() == PluginState.STARTED
                    ? PluginRuntimePackagePhase.STARTED : PluginRuntimePackagePhase.LOADED;
            packageIndex.add(
                    pluginId,
                    artifactPath,
                    pf4jLoadPath,
                    wrapper.getDescriptor().getVersion(),
                    phase,
                    packageDescriptor,
                    productionSnapshot
            );
        } catch (Throwable retentionFailure) {
            addSuppressedSafely(primaryFailure, retentionFailure);
            retainUnconfirmedProductionSnapshot(productionSnapshot);
        }
    }

    /** PF4J 可能先改变 wrapper 状态再抛错；错误边界前把本地 entry 对齐到可观测事实。 */
    private void reconcileEntryWithWrapper(
            Entry entry, PluginRuntimePackagePhase nonStartedPhase, Throwable primaryFailure) {
        try {
            PluginWrapper wrapper = pluginManager.getPlugin(entry.packageId());
            if (wrapper == null) {
                Entry removed = packageIndex.remove(entry.packageId());
                // 本方法只在 PF4J 原语抛错或返回异常状态时调用；wrapper 消失不等于 classloader 已释放。
                retainUnconfirmedProductionSnapshot(removed);
                return;
            }
            entry.updatePhase(wrapper.getPluginState() == PluginState.STARTED
                    ? PluginRuntimePackagePhase.STARTED : nonStartedPhase);
        } catch (Throwable inspectionFailure) {
            addSuppressedSafely(primaryFailure, inspectionFailure);
        }
    }

    private void refreshStatusSafely(Throwable primaryFailure) {
        try {
            refreshStatus();
        } catch (Throwable refreshFailure) {
            addSuppressedSafely(primaryFailure, refreshFailure);
        }
    }

    private static PluginRuntimeOperationException operationFailure(String message, Throwable failure) {
        rethrowFatal(failure);
        return new PluginRuntimeOperationException(message, failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败。
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    private static Function<PluginPackageOrigin, PluginSupplyChainVerifier> fixedVerifier(
            PluginSupplyChainVerifier verifier) {
        PluginSupplyChainVerifier fixed = Objects.requireNonNull(verifier, "verifier");
        return origin -> fixed;
    }

}
