package top.sywyar.pixivdownload.plugin.runtime.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustDecision;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustRequirement;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageFormat;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshotter;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshotter.Artifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryArtifactInspector;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryArtifactInspector.LogicalArtifactState;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryArtifactSnapshotter;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryArtifactSnapshotter.BackupPath;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryVisibleInventoryVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryVisibleInventoryVerifier.VisibleArtifactInventory;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestStore;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestStore.ReadException;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestStore.ReadResult;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.ExpectedArtifact;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryBackup;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryManifest;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestValidator.RecoveryOperation;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryManifestWriter;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryPlan;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryPlanSet;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryResourceBudget;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryWorkspaceCleaner;
import top.sywyar.pixivdownload.plugin.runtime.install.recovery.PluginRecoveryValidationException;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginDirectorySessionLock;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRemovalAttempt;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.Failure;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVersion;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.ZipSafety;

/**
 * 外置插件安装器：把一个 {@code .zip} / {@code .jar} 安装包安全地装入运行时插件目录
 * （{@code RuntimeFiles.pluginsDirectory()}，与 PF4J 扫描的目录同一处）。<b>不</b>污染 app classpath、<b>不</b>写源码目录、
 * <b>不</b>做热加载——落盘后于下次启动 / 下次扫描由 {@code PluginRuntimeManager} 发现并加载。
 *
 * <h2>安装流程（原子、失败不留半成品）</h2>
 * <ol>
 *   <li><b>检视</b>：{@link PluginPackageReader#inspect} 读出布局 + 包级描述符；非法包（空 / 缺描述符 / 歧义 / 损坏）
 *       直接返回对应拒绝结果，<b>零落盘</b>。</li>
 *   <li><b>资源规模安全扫描</b>：在任何解压 / 落盘前对 {@code .zip} / {@code .jar} 做 {@link PluginPackageVerifier#verify}
 *       （归档体积 / entry 数 / 单 entry 与总解压字节 / 压缩比上限，防 Zip Bomb），超限 → {@code REJECTED_TOO_LARGE}。</li>
 *   <li><b>供应链验签</b>：{@link PluginArtifactVerificationService} 比对来源 {@link PluginPackageOrigin} 声明的期望
 *       （大小 / SHA-256 / 结构化签名）。本地上传按显式 unsigned 策略处理；受信目录来源不符 →
 *       {@code REJECTED_INTEGRITY}。</li>
 *   <li><b>校验描述符</b>：{@link PluginDescriptor#externalValidationErrors()} 不通过 → {@code REJECTED_INVALID}。</li>
 *   <li><b>SDK 兼容门</b>：{@code requires} 不被当前宿主 SDK 满足 → {@code REJECTED_INCOMPATIBLE}（不装为可加载状态）。</li>
 *   <li><b>ZIP entry 校验</b>：对 {@code .zip} 包做 {@link ZipSafety#assertSafeArchiveEntries}，含越界、不可移植或规范化重名 entry → {@code REJECTED_UNSAFE}。</li>
 *   <li><b>重复 / 升级 / 降级</b>：按 pluginId 找安装目录内现存同 id 包并比 semver——无→{@code INSTALLED}；
 *       高→{@code UPGRADED}；同→{@code DUPLICATE}（幂等）；低→默认 {@code DOWNGRADE_REJECTED}，
 *       {@code allowDowngrade=true} 时 {@code DOWNGRADED}。</li>
 *   <li><b>提交（事务化）</b>：先把规范命名的产物（{@code {id}-{version}.jar|.zip}）写到安装目录下的隐藏暂存子目录
 *       {@code .staging/{opId}/}（与目标同卷）；再把<b>同 id 被取代旧包</b>（规范目标自身除外）以 no-clobber hardlink 发布后移除源名的方式移入隔离备份
 *       {@code .staging/{opId}/removed/}；随后把新包移动到位；最后 best-effort 删除隔离备份与暂存。
 *       <b>隔离任一旧包失败</b>即回滚已隔离者、返回 {@code FAILED}，<b>绝不放置新包</b>；<b>放置新包失败</b>则还原被隔离旧包、
 *       返回 {@code FAILED}。故每个 {@code accepted} 结局都保证安装目录里同 pluginId 只剩规范目标包（同 id 至多 1 个可见包），
 *       拒绝 / 失败时既不残留半成品、也不产生同 id 多版本可见文件。</li>
 * </ol>
 *
 * <p>规范命名 {@code {id}-{version}.{ext}} 用<b>描述符</b>的 id / version（描述符权威，与上传文件名无关），ext 取布局
 * （单 jar→{@code .jar}、解压目录→{@code .zip}）。这保证同 id 不同版本可识别、不产生重名副本。隔离备份位于以 {@code .}
 * 开头的暂存目录内，{@link #listInstalled()} 与 PF4J 扫描都跳过它，故即便备份清理失败也不会被当成已安装包。
 *
 * <p>POJO（无 Spring 注解），由核心壳侧配置 {@code @Bean} 装配（注入 {@code RuntimeFiles.pluginsDirectory()}）。
 * 构造不创建目录、无副作用；安装目录在首次 {@link #prepareTransaction} 时按需创建（目录创建本就归安装流程）。
 * 本安装器只负责落盘，不校验插件主类是否实现入口契约（需加载类，属运行期发现桥接）。
 *
 * <h2>并发串行化</h2>
 * {@link #prepareTransaction} / {@link #commitTransaction} 从权威枚举到提交落盘是一段「检查后动作」临界区；本实例用一把
 * {@link ReentrantLock} 把单实例调用串行化；{@link PluginDirectorySessionLock} 再以 JVM owner claim + 文件锁覆盖
 * 同进程其它实例和其它进程，并从启动恢复持续持有到 bootstrap session 关闭。owner claim 只在 lease 存活期间保留规范化
 * 目录路径，不持 classloader；每个文件入口都会复核锁路径仍指向本会话的 identity range，换绑即 fail-closed。
 * 该协议串行化遵守同一 lease 的协作进程；同一 OS 账号 / 管理员在持锁期间主动改写父目录或持续抢占检查—操作窗口，
 * 必须由部署侧 ACL / 独立账号隔离，纯 JDK {@link Path} 操作不声称消除此类竞态。
 *
 * <h2>来源</h2>
 * {@link #prepareTransaction} 接受 {@link PluginPackageOrigin}：本地上传使用
 * {@link PluginPackageSource#LOCAL_UPLOAD}，受信 catalog 携带大小 / SHA-256 / 签名期望；本类自身<b>不</b>发起网络访问。
 */
public class ExternalPluginInstaller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginInstaller.class);

    /** 安装目录内的隐藏暂存子目录名（以 {@code .} 开头，PF4J 扫描会跳过它）。 */
    static final String STAGING_DIR = ".staging";

    /** 尚未对恢复器发布的事务工作区；只有完整清单与 artifact 都落盘后才原子移动到 {@link #STAGING_DIR}。 */
    private static final String PREPARATION_DIR = ".preparing";

    /** 已完成状态从恢复扫描面原子退役后的隐藏清理区；其中残留不再影响插件加载安全。 */
    private static final String FINALIZATION_DIR = ".transaction-cleanup";

    private final Path pluginsDir;
    private final PluginPackageLimits limits;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private PluginArtifactVerificationService verificationService;
    private final BooleanSupplier developmentModeEnabled;
    private final PluginProvenanceStore provenanceStore;
    private final InstalledPluginInventorySnapshotter inventorySnapshotter;
    private final PluginRecoveryManifestValidator recoveryManifestValidator;
    private final PluginRecoveryArtifactInspector recoveryArtifactInspector;
    private final PluginRecoveryArtifactSnapshotter recoveryArtifactSnapshotter;
    private final PluginRecoveryVisibleInventoryVerifier recoveryVisibleInventoryVerifier;
    private final PluginRecoveryManifestWriter recoveryManifestWriter;
    private final PluginRecoveryWorkspaceCleaner recoveryWorkspaceCleaner;
    private final PluginDirectorySessionLock directorySessionLock;
    /** 把恢复、权威枚举与文件事务串行化（同一实例 / 同一安装目录的并发操作互斥）。 */
    private final ReentrantLock installLock = new ReentrantLock();

    /** 启动期最多枚举的待恢复事务数；超过即在任何事务写入前 fail-closed。 */
    private static final int MAX_RECOVERY_TRANSACTIONS = 256;

    private static final int MAX_HIDDEN_WORKSPACES = 256;
    private static final int MAX_HIDDEN_WORKSPACE_ENTRIES = 8_192;
    private static final int MAX_MANAGED_CLEANUP_ENTRIES = 8_192;

    /** 恢复安全门从未检查开始；只有显式成功恢复才能开放扫描和写入口。 */
    /** 状态与报告必须原子发布，避免读方观察到 SAFE + 失败报告等不可能组合。 */
    private volatile PluginRecoveryGateSnapshot recoveryGate = PluginRecoveryGateSnapshot.unchecked();

    public ExternalPluginInstaller(Path pluginsDir) {
        this(pluginsDir, PluginPackageLimits.defaults());
    }

    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits) {
        this(pluginsDir, limits, new PluginSupplyChainVerifier());
    }

    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                   PluginSupplyChainVerifier verifier) {
        this(pluginsDir, limits, fixedVerifier(verifier));
    }

    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                   Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this(pluginsDir, limits, verifierResolver,
                new PluginDirectorySessionLock(Objects.requireNonNull(pluginsDir, "pluginsDir")), false,
                PluginDevelopmentArtifacts::enabled);
    }

    /** bootstrap 会话使用的构造入口；目录锁由同一会话持有到进程 / context 生命周期结束。 */
    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                   Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                   PluginDirectorySessionLock directorySessionLock) {
        this(pluginsDir, limits, verifierResolver,
                Objects.requireNonNull(directorySessionLock, "directorySessionLock"), false,
                PluginDevelopmentArtifacts::enabled);
    }

    /** bootstrap 会话使用的构造入口；开发态准入开关由同一会话显式提供。 */
    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                   Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                   PluginDirectorySessionLock directorySessionLock,
                                   BooleanSupplier developmentModeEnabled) {
        this(pluginsDir, limits, verifierResolver,
                Objects.requireNonNull(directorySessionLock, "directorySessionLock"), false,
                developmentModeEnabled);
    }

    private ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                    Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                    PluginDirectorySessionLock directorySessionLock,
                                    boolean isolatedWithoutDirectoryLock,
                                    BooleanSupplier developmentModeEnabled) {
        if (pluginsDir == null) {
            throw new IllegalArgumentException("pluginsDir must not be null");
        }
        Path normalizedPluginsDir = pluginsDir.toAbsolutePath().normalize();
        if (!isolatedWithoutDirectoryLock) {
            PluginDirectorySessionLock suppliedLock = Objects.requireNonNull(
                    directorySessionLock, "directorySessionLock");
            Path lockedRoot = suppliedLock.lockPath().toAbsolutePath().normalize().getParent();
            if (!normalizedPluginsDir.equals(lockedRoot)) {
                throw new IllegalArgumentException("plugin directory lock protects a different root: "
                        + lockedRoot);
            }
        }
        this.pluginsDir = normalizedPluginsDir;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.developmentModeEnabled = Objects.requireNonNull(developmentModeEnabled, "developmentModeEnabled");
        this.verificationService = new PluginArtifactVerificationService(
                this.verifierResolver, this.developmentModeEnabled);
        this.provenanceStore = new PluginProvenanceStore(this.pluginsDir);
        this.inventorySnapshotter = new InstalledPluginInventorySnapshotter(provenanceStore);
        this.recoveryManifestValidator = new PluginRecoveryManifestValidator(
                this.pluginsDir, this.limits, this.provenanceStore);
        this.recoveryArtifactInspector = new PluginRecoveryArtifactInspector(
                this.limits, this.provenanceStore);
        this.recoveryArtifactSnapshotter = new PluginRecoveryArtifactSnapshotter(
                this.limits, this.provenanceStore);
        this.recoveryVisibleInventoryVerifier = new PluginRecoveryVisibleInventoryVerifier(
                this.pluginsDir,
                this.limits,
                this.recoveryArtifactInspector,
                this.provenanceStore);
        this.recoveryManifestWriter = new PluginRecoveryManifestWriter(
                this.recoveryArtifactInspector,
                this.recoveryVisibleInventoryVerifier);
        this.recoveryWorkspaceCleaner = new PluginRecoveryWorkspaceCleaner(
                MAX_HIDDEN_WORKSPACES,
                MAX_HIDDEN_WORKSPACE_ENTRIES);
        this.directorySessionLock = isolatedWithoutDirectoryLock
                ? directorySessionLock
                : Objects.requireNonNull(directorySessionLock, "directorySessionLock");
    }

    /** 规范化后的绝对安装目录。 */
    public Path pluginsDirectory() {
        return pluginsDir;
    }

    /** 由宿主在配置解析后刷新统一验签门面；插件代码没有调用入口。 */
    public void updateVerifier(PluginSupplyChainVerifier verifier) {
        updateVerifierResolver(fixedVerifier(verifier));
    }

    /** 由宿主在配置解析后刷新按来源解析的验签门面；插件代码没有调用入口。 */
    public void updateVerifierResolver(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        installLock.lock();
        try {
            this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
            this.verificationService = new PluginArtifactVerificationService(
                    this.verifierResolver, this.developmentModeEnabled);
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 启动恢复是否已证明本进程可继续安装 / 加载插件。失败一旦记录，本进程内不会自动解除；磁盘人工修复后必须重启，
     * 防止后端 restart 或后续管理动作绕过启动期 fail-closed 结论。
     */
    public boolean recoverySafeForRuntime() {
        return recoveryGate.safeToScan();
    }

    /** 纯内存恢复准入快照；BLOCKED 消费方不得为补充状态而重新枚举插件目录。 */
    public PluginRecoveryGateSnapshot recoveryGateSnapshot() {
        return recoveryGate;
    }

    /** bootstrap 防御性兜底：恢复调用意外抛错时，把结构化失败固化为本进程运行时安全门。 */
    public void blockRuntimeOperations(PluginTransactionRecoveryReport report) {
        PluginTransactionRecoveryReport effective = Objects.requireNonNull(report, "report");
        if (effective.safeToScan()) {
            return;
        }
        installLock.lock();
        try {
            if (recoveryGate.state() != PluginRecoveryGateState.BLOCKED) {
                recoveryGate = PluginRecoveryGateSnapshot.blocked(effective);
            }
        } finally {
            installLock.unlock();
        }
    }

    private boolean acquireDirectorySessionLock(boolean createRoot) {
        if (directorySessionLock == null) {
            return true;
        }
        boolean heldBefore = directorySessionLock.held();
        boolean rootPresent;
        try {
            if (createRoot) {
                directorySessionLock.acquireForMutation();
                rootPresent = true;
            } else {
                rootPresent = directorySessionLock.acquireIfRootExists();
            }
        } catch (IOException e) {
            PluginTransactionRecoveryReport report = directoryLockFailureReport(e);
            blockRuntimeOperations(report);
            throw new IllegalStateException("could not acquire the plugin directory session lock", e);
        }
        if (rootPresent && !heldBefore && recoveryGate.state() == PluginRecoveryGateState.SAFE) {
            PluginTransactionRecoveryReport lateRecovery;
            Throwable lateFailure = null;
            try {
                lateRecovery = recoverPendingTransactionsExclusive();
            } catch (Throwable e) {
                lateFailure = e;
                lateRecovery = new PluginTransactionRecoveryReport(List.of(recoveryFailure(
                        STAGING_DIR, pluginsDir.toAbsolutePath().normalize().resolve(STAGING_DIR),
                        FailureKind.RECOVERY_FAILED,
                        "unexpected late recovery failure: " + describeRecoveryFailure(e))));
            }
            if (lateRecovery.safeToScan()) {
                recoveryGate = PluginRecoveryGateSnapshot.safe(lateRecovery);
            } else {
                blockRuntimeOperations(lateRecovery);
            }
            rethrowIfFatal(lateFailure);
        }
        return rootPresent;
    }

    private boolean acquireDirectorySessionLockIfPresent() {
        return acquireDirectorySessionLock(false);
    }

    private void acquireDirectorySessionLockForMutation() {
        acquireDirectorySessionLock(true);
    }

    private PluginTransactionRecoveryReport directoryLockFailureReport(IOException failure) {
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path stagingRoot = pluginsRoot.resolve(STAGING_DIR);
        try {
            assertExistingPathComponentsSafe(pluginsRoot, pluginsRoot, "plugins root");
        } catch (PluginRecoveryValidationException e) {
            return new PluginTransactionRecoveryReport(List.of(recoveryFailure(
                    STAGING_DIR, stagingRoot, FailureKind.STAGING_ROOT_UNSAFE, e.getMessage())));
        }
        return new PluginTransactionRecoveryReport(List.of(recoveryFailure(
                STAGING_DIR, stagingRoot, FailureKind.RECOVERY_FAILED,
                "plugin directory session lock unavailable: " + describeRecoveryFailure(failure))));
    }

    /**
     * PF4J 启动或显式加载在触碰插件 entry 前调用。若安装根是在首次恢复后才出现，则先取得会话锁并在同一锁下
     * 补做一次恢复；目录仍缺失时只复核内存安全门，不为开发模式创建生产安装根。
     */
    public void prepareRuntimeScan() {
        installLock.lock();
        try {
            if (directorySessionLock == null) {
                requireRecoverySafe("scan plugin runtime");
                return;
            }
            if (!acquireDirectorySessionLock(false)) {
                requireRecoverySafe("scan plugin runtime");
                return;
            }
            requireRecoverySafe("scan plugin runtime");
        } finally {
            installLock.unlock();
        }
    }

    private void requireRecoverySafe(String operation) {
        PluginRecoveryGateSnapshot snapshot = recoveryGate;
        PluginRecoveryGateState state = snapshot.state();
        if (state == PluginRecoveryGateState.UNCHECKED) {
            throw new IllegalStateException("plugin transaction recovery has not completed; refusing to "
                    + operation);
        }
        if (state == PluginRecoveryGateState.BLOCKED) {
            PluginTransactionRecoveryReport blocked = snapshot.report();
            throw new IllegalStateException("plugin transaction recovery is unsafe; refusing to " + operation
                    + " until the process is restarted after recovery (failures="
                    + blocked.failures().size() + ")");
        }
    }

    /** 仅供未发布的隔离 validation root 产出已校验 artifact；正式安装必须走持久化生命周期事务。 */
    private PluginInstallResult installIsolated(Path packagePath, PluginPackageOrigin origin) {
        installLock.lock();
        try {
            requireRecoverySafe("materialize isolated validation artifact");
            return installExclusive(packagePath, true,
                    origin != null ? origin : PluginPackageOrigin.localUpload());
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 完成全部包安全校验并产出 staged artifact，但不移动、删除或覆盖任何现有插件包。
     * staged artifact 位于 plugins/.staging/{transactionId}/new/，供统一生命周期编排器缩短停机窗口。
     */
    public PreparedPluginTransaction prepareTransaction(Path packagePath, boolean allowDowngrade,
                                                        PluginPackageOrigin origin) {
        installLock.lock();
        Path unpublishedTransaction = null;
        Path publishedTransaction = null;
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("prepare transaction");
            String transactionId = UUID.randomUUID().toString();
            Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
            Path transaction = pluginsRoot.resolve(STAGING_DIR).resolve(transactionId);
            publishedTransaction = transaction;
            unpublishedTransaction = createUnpublishedTransaction(pluginsRoot, transactionId);
            Path validationDir = unpublishedTransaction.resolve("validation");
            PluginPackageOrigin effectiveOrigin = origin != null ? origin : PluginPackageOrigin.localUpload();
            ExternalPluginInstaller validator = new ExternalPluginInstaller(
                    validationDir, limits, verifierResolver, null, true, developmentModeEnabled);
            PluginTransactionRecoveryReport validationRecovery = validator.recoverPendingTransactions();
            if (!validationRecovery.safeToScan()) {
                throw new IOException("isolated validation directory did not pass recovery safety checks");
            }
            PluginInstallResult validated = validator.installIsolated(packagePath, effectiveOrigin);
            if (!validated.accepted() || validated.descriptor() == null || validated.installedPath() == null) {
                deleteRecursivelyQuietly(unpublishedTransaction);
                return new PreparedPluginTransaction(transactionId, validated, null, null, null, List.of());
            }

            PluginDescriptor descriptor = validated.descriptor();
            PluginProvenanceRecord validatedProvenance = validator.provenanceStore
                    .readRequiredForRecovery(validated.installedPath());
            List<InstalledPlugin> installed = listInstalledExclusive();
            List<InstalledPlugin> sameId = installed.stream()
                    .filter(plugin -> descriptor.id().equals(plugin.id())).toList();
            List<InstalledPlugin> replaced = installed.stream()
                    .filter(plugin -> descriptor.replaces().contains(plugin.id())).toList();
            InstalledPlugin highest = sameId.stream()
                    .max(Comparator.comparing(plugin -> PluginPackageVersion.parse(plugin.version())))
                    .orElse(null);
            PluginInstallOutcome outcome;
            String previousVersion = highest != null ? highest.version() : null;
            if (highest == null) {
                outcome = PluginInstallOutcome.INSTALLED;
            } else {
                int compare = PluginPackageVersion.parse(descriptor.version())
                        .compareTo(PluginPackageVersion.parse(highest.version()));
                if (compare > 0) {
                    outcome = PluginInstallOutcome.UPGRADED;
                } else if (compare == 0) {
                    outcome = PluginInstallOutcome.DUPLICATE;
                } else if (allowDowngrade) {
                    outcome = PluginInstallOutcome.DOWNGRADED;
                } else {
                    deleteRecursivelyQuietly(unpublishedTransaction);
                    PluginInstallResult rejected = new PluginInstallResult(PluginInstallOutcome.DOWNGRADE_REJECTED,
                            descriptor, null, previousVersion, List.of("refusing to downgrade " + descriptor.id()
                            + " from " + previousVersion + " to " + descriptor.version() + " (force required)"));
                    return new PreparedPluginTransaction(transactionId, rejected, null, null, null,
                            sameId.stream().map(InstalledPlugin::path).toList());
                }
            }
            PluginInstallResult identityRejection = rejectIdentityDiscontinuity(
                    descriptor, validatedProvenance, sameId, replaced, previousVersion,
                    effectiveOrigin);
            if (identityRejection != null) {
                deleteRecursivelyQuietly(unpublishedTransaction);
                return new PreparedPluginTransaction(transactionId, identityRejection,
                        null, null, null, List.of());
            }
            TrustResolution trust = resolveTrust(
                    descriptor, validatedProvenance, sameId, replaced, previousVersion, effectiveOrigin);
            if (trust.rejection() != null) {
                deleteRecursivelyQuietly(unpublishedTransaction);
                return new PreparedPluginTransaction(transactionId, trust.rejection(),
                        null, null, null, List.of());
            }
            validatedProvenance = trust.provenance();

            if (outcome == PluginInstallOutcome.DUPLICATE) {
                deleteRecursivelyQuietly(unpublishedTransaction);
                if (sameId.size() != 1) {
                    PluginInstallResult rejected = new PluginInstallResult(
                            PluginInstallOutcome.REJECTED_INTEGRITY, descriptor, null, previousVersion,
                            List.of("multiple installed artifacts share plugin id " + descriptor.id()));
                    return new PreparedPluginTransaction(transactionId, rejected,
                            null, null, null, List.of());
                }
                PluginProvenanceRecord installedProvenance = readIdentityProvenance(highest, descriptor);
                if (installedProvenance == null
                        || !installedProvenance.artifactSha256().equals(validatedProvenance.artifactSha256())) {
                    PluginInstallResult rejected = new PluginInstallResult(
                            PluginInstallOutcome.REJECTED_INTEGRITY, descriptor, null, previousVersion,
                            List.of("same plugin version has different artifact bytes"));
                    return new PreparedPluginTransaction(transactionId, rejected,
                            null, null, null, List.of());
                }
                try {
                    provenanceStore.write(highest.path(), validatedProvenance);
                } catch (IOException e) {
                    PluginInstallResult failed = new PluginInstallResult(
                            PluginInstallOutcome.FAILED, descriptor, null, previousVersion,
                            List.of("failed to persist plugin trust: " + e.getMessage()));
                    return new PreparedPluginTransaction(transactionId, failed,
                            null, null, null, List.of());
                }
                PluginInstallResult duplicate = new PluginInstallResult(PluginInstallOutcome.DUPLICATE,
                        descriptor, highest.path(), previousVersion,
                        List.of(descriptor.id() + " " + descriptor.version() + " already installed"));
                return new PreparedPluginTransaction(transactionId, duplicate, null, null, null,
                        List.of(highest.path()));
            }

            Files.createDirectories(unpublishedTransaction.resolve("new"));
            Path unpublishedArtifact = unpublishedTransaction.resolve("new")
                    .resolve(validated.installedPath().getFileName());
            moveIntoPlace(validated.installedPath(), unpublishedArtifact);
            provenanceStore.write(unpublishedArtifact, validatedProvenance);
            validator.provenanceStore.delete(validated.installedPath());
            deleteRecursivelyQuietly(validationDir);
            Path staged = transaction.resolve("new").resolve(unpublishedArtifact.getFileName());
            Path target = pluginsRoot.resolve(staged.getFileName()).normalize();
            PluginInstallResult result = new PluginInstallResult(outcome, descriptor, target, previousVersion,
                    List.of(outcome + " " + descriptor.id() + " " + descriptor.version()));
            List<Path> expectedCurrent = Stream.concat(sameId.stream(), replaced.stream())
                    .map(InstalledPlugin::path)
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct().sorted().toList();
            List<CommittedPluginTransaction.BackupArtifact> declaredBackups = new ArrayList<>();
            Path backupDir = transaction.resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY);
            for (Path currentArtifact : expectedCurrent) {
                declaredBackups.add(new CommittedPluginTransaction.BackupArtifact(currentArtifact,
                        backupDir.resolve(declaredBackups.size() + "-" + currentArtifact.getFileName())));
            }
            ExpectedArtifact newArtifact = recoveryArtifactSnapshotter.snapshotInstallArtifact(
                    unpublishedArtifact,
                    unpublishedArtifact,
                    descriptor.id(),
                    descriptor.version(),
                    verificationService);
            List<RecoveryBackup> frozenBackups = recoveryArtifactSnapshotter.freezeInstallBackups(
                    null,
                    declaredBackups);
            RecoveryManifest publishedManifest = new RecoveryManifest(
                    RecoveryOperation.INSTALL, PluginTransactionState.PREPARED,
                    descriptor.id(), descriptor.version(), target, staged, newArtifact,
                    List.copyOf(descriptor.replaces()), frozenBackups);
            RecoveryManifest unpublishedManifest = relocateManifestToTransaction(
                    publishedManifest, unpublishedTransaction);
            recoveryManifestWriter.persist(
                    unpublishedTransaction,
                    transactionId,
                    unpublishedManifest,
                    publishedManifest,
                    "PixivDownloader plugin transaction",
                    verificationService);
            beforeInstallTransactionPublished(unpublishedTransaction);
            publishTransaction(unpublishedTransaction, transaction);
            unpublishedTransaction = null;
            afterInstallTransactionPublished(transaction);
            validatePublishedTransaction(pluginsRoot, transaction);
            PreparedPluginTransaction prepared = new PreparedPluginTransaction(transactionId, result, transaction,
                    staged, target, expectedCurrent);
            return prepared;
        } catch (Throwable e) {
            PublishedTransactionFailure publishedFailure =
                    blockPublishedTransactionIfPresent(publishedTransaction, e);
            Throwable terminalFailure = mergeUnpublishedCleanupFailure(
                    unpublishedTransaction, publishedFailure.failure());
            rethrowIfError(terminalFailure);
            if (terminalFailure instanceof RuntimeException runtimeFailure
                    && !publishedFailure.publishedOrUncertain()) {
                throw runtimeFailure;
            }
            return new PreparedPluginTransaction(UUID.randomUUID().toString(),
                    new PluginInstallResult(PluginInstallOutcome.FAILED, null, null, null,
                            List.of("failed to stage plugin transaction: "
                                    + describeRecoveryFailure(terminalFailure))),
                    null, null, null, List.of());
        } finally {
            installLock.unlock();
        }
    }

    private PluginInstallResult rejectIdentityDiscontinuity(
            PluginDescriptor descriptor,
            PluginProvenanceRecord candidate,
            List<InstalledPlugin> sameId,
            List<InstalledPlugin> replaced,
            String previousVersion,
            PluginPackageOrigin origin) {
        InstalledPlugin confirmationRequired = null;
        for (InstalledPlugin current : sameId) {
            PluginProvenanceRecord installed = readIdentityProvenance(current, descriptor);
            IdentityMigrationDecision decision = installed == null || sameTrustOwner(installed, candidate)
                    ? IdentityMigrationDecision.AUTHORIZED
                    : identityMigrationDecision(current, installed, descriptor, candidate, origin);
            if (installed == null || decision == IdentityMigrationDecision.REJECTED) {
                return identityRejected(descriptor, previousVersion, current,
                        installed == null ? "installed provenance is missing or invalid"
                                : "trust owner changed");
            }
            if (decision == IdentityMigrationDecision.CONFIRMATION_REQUIRED) {
                confirmationRequired = current;
            }
        }
        for (InstalledPlugin current : replaced) {
            PluginProvenanceRecord installed = readIdentityProvenance(current, descriptor);
            IdentityMigrationDecision decision = installed == null || installed.signature() == null
                    || candidate.signature() == null
                    ? IdentityMigrationDecision.REJECTED
                    : sameTrustOwner(installed, candidate)
                            ? IdentityMigrationDecision.AUTHORIZED
                            : identityMigrationDecision(current, installed, descriptor, candidate, origin);
            if (decision == IdentityMigrationDecision.REJECTED) {
                return identityRejected(descriptor, previousVersion, current,
                        installed == null ? "installed provenance is missing or invalid"
                                : "replacement is not authorized by the installed trust owner");
            }
            if (decision == IdentityMigrationDecision.CONFIRMATION_REQUIRED) {
                confirmationRequired = current;
            }
        }
        return confirmationRequired == null ? null
                : identityConfirmationRequired(descriptor, previousVersion, confirmationRequired);
    }

    private TrustResolution resolveTrust(
            PluginDescriptor descriptor,
            PluginProvenanceRecord candidate,
            List<InstalledPlugin> sameId,
            List<InstalledPlugin> replaced,
            String previousVersion,
            PluginPackageOrigin origin) {
        if (candidate.developmentOnly()) {
            return new TrustResolution(candidate, null);
        }
        List<PluginProvenanceRecord> installed = Stream.concat(sameId.stream(), replaced.stream())
                .map(plugin -> readIdentityProvenance(plugin, descriptor))
                .filter(Objects::nonNull)
                .toList();
        boolean previouslyRevoked = installed.stream()
                .anyMatch(record -> record.trustRevokedAt() != null);
        if (candidate.officialRepository() && !previouslyRevoked) {
            return new TrustResolution(candidate.withTrustDecision(
                    PluginTrustPolicy.official(descriptor, candidate, Instant.now())), null);
        }
        if (sameId.size() == 1 && replaced.isEmpty()) {
            PluginTrustDecision inherited = PluginTrustPolicy.inherited(
                    descriptor, candidate, installed.get(0));
            if (inherited != null) {
                return new TrustResolution(candidate.withTrustDecision(inherited), null);
            }
        }
        if (candidate.artifactSha256().equals(origin.trustConfirmationSha256())) {
            return new TrustResolution(candidate.withTrustDecision(
                    PluginTrustPolicy.approve(descriptor, candidate, Instant.now())), null);
        }
        PluginTrustRequirement requirement = PluginTrustPolicy.requirement(descriptor, candidate);
        PluginInstallResult rejection = new PluginInstallResult(
                PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED,
                descriptor,
                null,
                previousVersion,
                List.of("execution trust confirmation required for exact artifact "
                        + candidate.artifactSha256()),
                requirement);
        return new TrustResolution(null, rejection);
    }

    private IdentityMigrationDecision identityMigrationDecision(
            InstalledPlugin current,
            PluginProvenanceRecord installed,
            PluginDescriptor descriptor,
            PluginProvenanceRecord candidate,
            PluginPackageOrigin origin) {
        if (installed.signature() == null || candidate.signature() == null) {
            return IdentityMigrationDecision.REJECTED;
        }
        var authorization = origin.identityMigrationSignatures().get(current.id());
        if (authorization != null) {
            VerificationResult result = verificationService.verifyIdentityMigration(
                    current.id(), installed, descriptor.id(), descriptor.version(), candidate, authorization);
            if (result.accepted()) {
                return IdentityMigrationDecision.AUTHORIZED;
            }
            log.warn("Plugin key identity migration authorization did not validate {} -> {}: {} ({})",
                    current.id(), descriptor.id(), result.status(), result.diagnosticCode());
        }
        var repositoryAuthorization = origin.repositoryIdentityMigrationAuthorizations().get(current.id());
        if (repositoryAuthorization == null) {
            return IdentityMigrationDecision.REJECTED;
        }
        VerificationResult result = verificationService.verifyRepositoryIdentityMigration(
                current.id(), installed, descriptor.id(), descriptor.version(), candidate, repositoryAuthorization);
        if (!result.accepted()) {
            log.warn("Rejecting repository-root plugin identity migration {} -> {}: {} ({})",
                    current.id(), descriptor.id(), result.status(), result.diagnosticCode());
            return IdentityMigrationDecision.REJECTED;
        }
        return origin.identityMigrationConfirmed()
                ? IdentityMigrationDecision.AUTHORIZED : IdentityMigrationDecision.CONFIRMATION_REQUIRED;
    }

    private PluginProvenanceRecord readIdentityProvenance(
            InstalledPlugin installed, PluginDescriptor descriptor) {
        try {
            return provenanceStore.readRequiredForRecovery(installed.path());
        } catch (IOException | RuntimeException e) {
            log.warn("Rejecting plugin {} because installed identity provenance for {} is unavailable: {}",
                    descriptor.id(), installed.id(), e.toString());
            return null;
        }
    }

    private static boolean sameTrustOwner(PluginProvenanceRecord installed, PluginProvenanceRecord candidate) {
        return installed.source() == candidate.source()
                && Objects.equals(installed.repositoryId(), candidate.repositoryId())
                && installed.officialRepository() == candidate.officialRepository()
                && installed.developmentOnly() == candidate.developmentOnly()
                && Objects.equals(installed.publisher(), candidate.publisher())
                && Objects.equals(installed.keyId(), candidate.keyId());
    }

    private static PluginInstallResult identityRejected(
            PluginDescriptor descriptor,
            String previousVersion,
            InstalledPlugin current,
            String reason) {
        return new PluginInstallResult(
                PluginInstallOutcome.REJECTED_INTEGRITY,
                descriptor,
                null,
                previousVersion,
                List.of("plugin identity continuity rejected for " + descriptor.id()
                        + " against installed " + current.id() + ": " + reason));
    }

    private static PluginInstallResult identityConfirmationRequired(
            PluginDescriptor descriptor,
            String previousVersion,
            InstalledPlugin current) {
        return new PluginInstallResult(
                PluginInstallOutcome.REJECTED_IDENTITY_CONFIRMATION_REQUIRED,
                descriptor,
                null,
                previousVersion,
                List.of("repository-root identity migration confirmation required for " + descriptor.id()
                        + " against installed " + current.id()));
    }

    private enum IdentityMigrationDecision {
        AUTHORIZED,
        CONFIRMATION_REQUIRED,
        REJECTED
    }

    private record TrustResolution(
            PluginProvenanceRecord provenance,
            PluginInstallResult rejection) {
    }

    /**
     * 在调用方已确认旧 generation 物理卸载后提交文件替换。旧包保留在 backup，直至 completeTransaction。
     */
    public CommittedPluginTransaction commitTransaction(PreparedPluginTransaction prepared) {
        if (prepared == null || !prepared.readyToCommit()) {
            throw new IllegalArgumentException("plugin transaction is not ready to commit");
        }
        installLock.lock();
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("commit transaction");
            List<Path> expected;
            try {
                expected = verifyCurrentArtifactsExclusive(prepared);
            } catch (Throwable verificationFailure) {
                blockAfterPublishedFailure(prepared.transactionDirectory(),
                        FailureKind.RECOVERY_FAILED, verificationFailure);
                rethrowIfError(verificationFailure);
                if (verificationFailure instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (verificationFailure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new IOException("unexpected transaction verification failure", verificationFailure);
            }
            Path backupDir = prepared.transactionDirectory()
                    .resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY);
            List<CommittedPluginTransaction.BackupArtifact> backups = new ArrayList<>();
            try {
                for (Path origin : expected) {
                    Path backup = backupDir.resolve(backups.size() + "-" + origin.getFileName());
                    backups.add(new CommittedPluginTransaction.BackupArtifact(origin, backup));
                }
                // 先持久化全部 origin -> backup 映射，再开始移动。进程在任一移动后崩溃时，
                // 启动恢复都能把已经存在的备份逐一放回原位。
                writeManifest(prepared, PluginTransactionState.PREPARED, backups);
                if (!backups.isEmpty()) {
                    Files.createDirectories(backupDir);
                }
                for (CommittedPluginTransaction.BackupArtifact backup : backups) {
                    moveArtifactWithSidecar(backup.origin(), backup.backup());
                }
                writeManifest(prepared, PluginTransactionState.OLD_ISOLATED, backups);
                afterOldArtifactsIsolated(prepared.transactionDirectory());
                moveArtifactWithSidecar(prepared.stagedArtifact(), prepared.target());
                writeManifest(prepared, PluginTransactionState.NEW_PLACED, backups);
                afterNewArtifactPlaced(prepared.transactionDirectory());
                CommittedPluginTransaction committed = new CommittedPluginTransaction(prepared, backups);
                prepared.confirmCommitState(PreparedPluginTransaction.CommitState.COMMITTED);
                return committed;
            } catch (Throwable failure) {
                try {
                    recoverFailedInstallTransaction(prepared);
                    prepared.confirmCommitState(PreparedPluginTransaction.CommitState.ROLLED_BACK);
                } catch (Throwable recoveryFailure) {
                    confirmPreparedUnsafe(prepared);
                    Throwable terminalFailure = mergeCompensationFailure(failure, recoveryFailure);
                    rethrowIfError(terminalFailure);
                    if (terminalFailure instanceof IOException ioFailure) {
                        throw ioFailure;
                    }
                    if (terminalFailure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw new IOException("unexpected plugin recovery failure", terminalFailure);
                }
                rethrowIfError(failure);
                if (failure instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new IOException("unexpected plugin commit failure", failure);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to commit plugin transaction " + prepared.transactionId(), e);
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 在静默和物理卸载前复核暂存期间安装态未变化；commit 内仍会再次复核。
     * 复核失败发生在任何旧代写入前，installer 会先确认 PREPARED 已安全退役；只有无法退役时才封闭 gate。
     */
    public void verifyCurrentArtifacts(PreparedPluginTransaction prepared) {
        if (prepared == null || !prepared.readyToCommit()) {
            throw new IllegalArgumentException("plugin transaction is not ready to verify");
        }
        installLock.lock();
        try {
            if (!acquireDirectorySessionLockIfPresent()) {
                throw new IllegalStateException("plugins root disappeared before transaction verification");
            }
            requireRecoverySafe("verify transaction");
            verifyCurrentArtifactsExclusive(prepared);
        } catch (Throwable e) {
            Throwable terminalFailure = e;
            boolean discarded = false;
            try {
                discarded = discardPrepared(prepared);
            } catch (Throwable discardFailure) {
                terminalFailure = mergeCompensationFailure(terminalFailure, discardFailure);
            }
            if (!discarded) {
                confirmPreparedUnsafe(prepared);
                blockAfterPublishedFailure(prepared != null ? prepared.transactionDirectory() : null,
                        FailureKind.RECOVERY_FAILED, terminalFailure);
            }
            rethrowIfError(terminalFailure);
            throw new IllegalStateException("failed to verify prepared plugin transaction", terminalFailure);
        } finally {
            installLock.unlock();
        }
    }

    /** 在任何 classloader / 插件入口构造前，按已冻结 manifest 再验放置后的 artifact 与 provenance。 */
    public void verifyCommittedTarget(CommittedPluginTransaction transaction) {
        installLock.lock();
        try {
            if (!acquireDirectorySessionLockIfPresent()) {
                throw new IllegalStateException("plugins root disappeared before committed target verification");
            }
            requireRecoverySafe("verify committed plugin target");
            RecoveryManifest manifest = requireManagedCommittedManifest(
                    transaction, PluginTransactionState.NEW_PLACED);
            recoveryVisibleInventoryVerifier.verifyActivatedTarget(manifest, verificationService);
        } catch (IOException | PluginRecoveryValidationException e) {
            // 调用方必须先按同一 committed handle 回滚；回滚失败才封闭 gate，避免自锁。
            throw new IllegalStateException("committed plugin target failed its frozen verification", e);
        } finally {
            installLock.unlock();
        }
    }

    private List<Path> verifyCurrentArtifactsExclusive(PreparedPluginTransaction prepared) throws IOException {
        RecoveryManifest manifest = requireManagedInstallManifest(prepared, PluginTransactionState.PREPARED);
        List<String> affectedIds = new ArrayList<>(manifest.replaces());
        affectedIds.add(manifest.packageId());
        List<Path> current = listInstalledExclusive().stream()
                .filter(plugin -> affectedIds.contains(plugin.id()))
                .map(InstalledPlugin::path).map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        List<Path> expected = manifest.backups().stream().map(RecoveryBackup::origin).sorted().toList();
        if (!current.equals(expected)) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "installed plugin set changed while transaction was staged");
        }
        try {
            recoveryArtifactInspector.validateTransactionTree(prepared.transactionDirectory(), manifest);
            recoveryArtifactInspector.validateState(manifest);
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("prepared plugin transaction bindings changed: " + e.getMessage(), e);
        }
        return expected;
    }

    /** 记录新 generation 已完成运行时激活。 */
    public void markActivated(CommittedPluginTransaction transaction) {
        installLock.lock();
        boolean persistenceAttempted = false;
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("mark transaction activated");
            persistenceAttempted = true;
            beforeActivationManifestPersisted(transaction.prepared().transactionDirectory());
            writeManifest(transaction.prepared(), PluginTransactionState.ACTIVATED, transaction.backups());
            transaction.confirmDurableState(CommittedPluginTransaction.DurableState.ACTIVATED);
            afterActivationManifestPersisted(transaction.prepared().transactionDirectory());
        } catch (Throwable failure) {
            if (!persistenceAttempted) {
                rethrowIfError(failure);
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
            }
            PluginTransactionState durable = reconcileDurableActivationAfterFailure(
                    transaction, failure, "persist plugin activation");
            if (durable != PluginTransactionState.NEW_PLACED) {
                rethrowIfFatal(failure);
                return;
            }
            rethrowIfError(failure);
            throw new IllegalStateException("failed to persist plugin activation", failure);
        } finally {
            installLock.unlock();
        }
    }

    /** 提交完成：删除旧包 backup 与事务清单。 */
    public void completeTransaction(CommittedPluginTransaction transaction) {
        installLock.lock();
        boolean persistenceAttempted = false;
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("complete transaction");
            RecoveryManifest manifest;
            try {
                persistenceAttempted = true;
                writeManifest(transaction.prepared(), PluginTransactionState.COMMITTED, transaction.backups());
                transaction.confirmDurableState(CommittedPluginTransaction.DurableState.COMMITTED);
                afterCommittedManifestPersisted(transaction.prepared().transactionDirectory());
                manifest = requireManagedCommittedManifest(transaction, PluginTransactionState.COMMITTED);
            } catch (Throwable persistenceFailure) {
                Throwable terminalFailure = persistenceFailure;
                if (!persistenceAttempted) {
                    rethrowIfError(persistenceFailure);
                    if (persistenceFailure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                }
                PluginTransactionState durable = reconcileDurableActivationAfterFailure(
                        transaction, persistenceFailure, "finalize plugin transaction");
                if (durable != PluginTransactionState.NEW_PLACED) {
                    if (durable == PluginTransactionState.COMMITTED) {
                        try {
                            retireCommittedTransaction(transaction);
                        } catch (Throwable retirementFailure) {
                            try {
                                retainCommittedTransactionAfterRetirementFailure(transaction, retirementFailure);
                                terminalFailure = mergeCompensationFailure(
                                        persistenceFailure, retirementFailure);
                            } catch (Throwable retentionFailure) {
                                terminalFailure = mergeCompensationFailure(
                                        persistenceFailure, retentionFailure);
                                rethrowIfError(terminalFailure);
                                throw new IllegalStateException(
                                        "failed to reconcile plugin transaction retirement",
                                        terminalFailure);
                            }
                        }
                    } else {
                        transaction.markRecoveryBlocked();
                        blockAfterPublishedFailure(transaction.prepared().transactionDirectory(),
                                FailureKind.RECOVERY_FAILED, persistenceFailure);
                    }
                    rethrowIfFatal(terminalFailure);
                    return;
                }
                rethrowIfError(persistenceFailure);
                throw new IllegalStateException("failed to finalize plugin transaction", persistenceFailure);
            }
            try {
                retireTransaction(new PluginRecoveryPlan(transaction.prepared().transactionId(),
                        transaction.prepared().transactionDirectory(), manifest));
                transaction.confirmDurableState(CommittedPluginTransaction.DurableState.RETIRED);
            } catch (Throwable retirementFailure) {
                retainCommittedTransactionAfterRetirementFailure(transaction, retirementFailure);
                rethrowIfFatal(retirementFailure);
            }
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 原子清单写入报错后重读权威状态；只有已落盘的激活终态才能保留新代。
     * NEW_PLACED 返回 false，由调用方先卸载新代再走正常回滚；无法证明时才封闭 gate。
     */
    private PluginTransactionState reconcileDurableActivationAfterFailure(
            CommittedPluginTransaction transaction, Throwable failure, String operation) {
        try {
            RecoveryManifest durable = requireManagedCommittedManifest(transaction,
                    PluginTransactionState.NEW_PLACED,
                    PluginTransactionState.ACTIVATED,
                    PluginTransactionState.COMMITTED);
            if (durable.state() == PluginTransactionState.NEW_PLACED) {
                return PluginTransactionState.NEW_PLACED;
            }
            recoveryVisibleInventoryVerifier.verifyActivatedTarget(durable, verificationService);
            transaction.confirmDurableState(durable.state() == PluginTransactionState.COMMITTED
                    ? CommittedPluginTransaction.DurableState.COMMITTED
                    : CommittedPluginTransaction.DurableState.ACTIVATED);
            log.warn("{} reported a failure after durable state {} was verified; keeping the activated plugin: {}",
                    operation, durable.state(), failure.toString());
            return durable.state();
        } catch (Throwable reconciliationFailure) {
            Throwable terminalFailure = mergeCompensationFailure(failure, reconciliationFailure);
            transaction.markRecoveryBlocked();
            blockAfterPublishedFailure(transaction != null && transaction.prepared() != null
                            ? transaction.prepared().transactionDirectory() : null,
                    FailureKind.RECOVERY_FAILED, terminalFailure);
            rethrowIfError(terminalFailure);
            throw new IllegalStateException(
                    "failed to reconcile durable plugin activation state", terminalFailure);
        }
    }

    private void retireCommittedTransaction(CommittedPluginTransaction transaction)
            throws IOException, PluginRecoveryValidationException {
        RecoveryManifest manifest = requireManagedCommittedManifest(
                transaction, PluginTransactionState.COMMITTED);
        retireTransaction(new PluginRecoveryPlan(transaction.prepared().transactionId(),
                transaction.prepared().transactionDirectory(), manifest));
        transaction.confirmDurableState(CommittedPluginTransaction.DurableState.RETIRED);
    }

    /** COMMITTED 已证明后的退役失败只留隐藏清理残留，不得倒退已激活的新代。 */
    private void retainCommittedTransactionAfterRetirementFailure(
            CommittedPluginTransaction transaction, Throwable failure) {
        Path transactionDirectory = transaction.prepared().transactionDirectory();
        try {
            if (readAttributesIfPresent(transactionDirectory).isPresent()) {
                RecoveryManifest durable = requireManagedCommittedManifest(
                        transaction, PluginTransactionState.COMMITTED);
                recoveryVisibleInventoryVerifier.verifyActivatedTarget(durable, verificationService);
                transaction.confirmDurableState(CommittedPluginTransaction.DurableState.COMMITTED);
                transaction.markRecoveryBlocked();
                blockAfterPublishedFailure(transactionDirectory, FailureKind.RECOVERY_FAILED, failure);
            } else {
                transaction.confirmDurableState(CommittedPluginTransaction.DurableState.RETIRED);
            }
            log.warn("Plugin transaction {} remains durably committed after retirement cleanup failed: {}",
                    transaction.prepared().transactionId(), failure.toString());
        } catch (Throwable reconciliationFailure) {
            Throwable terminalFailure = mergeCompensationFailure(failure, reconciliationFailure);
            transaction.markRecoveryBlocked();
            blockAfterPublishedFailure(
                    transactionDirectory, FailureKind.RECOVERY_FAILED, terminalFailure);
            rethrowIfError(terminalFailure);
            throw new IllegalStateException(
                    "failed to reconcile committed plugin transaction", terminalFailure);
        }
    }

    /** 删除新包并把 backup 原样恢复到旧规范路径。 */
    public boolean rollbackTransaction(CommittedPluginTransaction transaction) {
        installLock.lock();
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("roll back transaction");
            RecoveryManifest manifest = requireManagedCommittedManifest(
                    transaction, PluginTransactionState.NEW_PLACED);
            PluginRecoveryPlan plan = new PluginRecoveryPlan(transaction.prepared().transactionId(),
                    transaction.prepared().transactionDirectory(), manifest);
            executeRecoveryPlan(plan);
            deleteStagingRootIfEmpty();
            boolean rolledBack = !Files.exists(
                    transaction.prepared().transactionDirectory(), LinkOption.NOFOLLOW_LINKS);
            if (rolledBack) {
                transaction.confirmDurableState(CommittedPluginTransaction.DurableState.ROLLED_BACK);
                transaction.prepared().confirmCommitState(PreparedPluginTransaction.CommitState.ROLLED_BACK);
            }
            return rolledBack;
        } catch (Throwable e) {
            if (transaction != null) {
                transaction.markRecoveryBlocked();
            }
            blockAfterPublishedFailure(transaction != null && transaction.prepared() != null
                            ? transaction.prepared().transactionDirectory() : null,
                    FailureKind.RECOVERY_FAILED, e);
            log.error("Failed to roll back plugin transaction {}: {}",
                    transaction.prepared().transactionId(), e.toString());
            rethrowIfError(e);
            return false;
        } finally {
            installLock.unlock();
        }
    }

    /** 放弃尚未提交的 staged artifact。 */
    public boolean discardPrepared(PreparedPluginTransaction prepared) {
        installLock.lock();
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("discard prepared transaction");
            if (prepared == null || prepared.transactionDirectory() == null) {
                return true;
            }
            try {
                Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
                Path transaction = requirePreparedTransactionPath(pluginsRoot, prepared);
                if (readAttributesIfPresent(transaction).isEmpty()) {
                    // commit 内部可能已经把失败事务完整回滚并退役；上层补偿再次 discard 必须幂等。
                    if (prepared.commitState() == PreparedPluginTransaction.CommitState.PREPARED) {
                        prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
                    }
                    return prepared.commitState() == PreparedPluginTransaction.CommitState.DISCARDED
                            || prepared.commitState() == PreparedPluginTransaction.CommitState.ROLLED_BACK;
                }
                PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
                if (plan == null) {
                    Files.delete(transaction);
                    deleteStagingRootIfEmpty();
                    prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
                    return true;
                }
                RecoveryManifest manifest = plan.manifest();
                requirePreparedMatchesManifest(prepared, manifest);
                if (manifest.operation() != RecoveryOperation.INSTALL
                        || manifest.state() != PluginTransactionState.PREPARED) {
                    log.error("Keeping plugin transaction {} because commit processing has already begun",
                            prepared.transactionId());
                    return false;
                }
                executeRecoveryPlan(plan);
                prepared.confirmCommitState(PreparedPluginTransaction.CommitState.DISCARDED);
                return true;
            } catch (Throwable e) {
                confirmPreparedUnsafe(prepared);
                blockAfterPublishedFailure(prepared.transactionDirectory(), FailureKind.RECOVERY_FAILED, e);
                log.error("Keeping plugin transaction {} because its recovery state could not be verified: {}",
                        prepared.transactionId(), e.toString());
                rethrowIfError(e);
                return false;
            }
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 启动扫描前逐事务恢复未完成事务：ACTIVATED / COMMITTED 清理已完成事务，其余状态优先恢复旧包。
     * 每个事务独立校验和恢复；坏事务不会阻断后续事务的检查，但会留在暂存目录并通过结构化结果要求调用方
     * fail-closed，不得继续 PF4J 扫描。
     */
    public PluginTransactionRecoveryReport recoverPendingTransactions() {
        installLock.lock();
        try {
            PluginRecoveryGateSnapshot current = recoveryGate;
            if (current.state() != PluginRecoveryGateState.UNCHECKED) {
                return current.report();
            }
            PluginTransactionRecoveryReport report;
            try {
                boolean rootPresent = directorySessionLock == null
                        || directorySessionLock.acquireIfRootExists();
                // 未持有目录 lease 时绝不枚举或清理；晚到的根由所有文件入口统一在首次取得 lease 后恢复。
                report = rootPresent
                        ? recoverPendingTransactionsExclusive()
                        : PluginTransactionRecoveryReport.success();
            } catch (IOException e) {
                report = directoryLockFailureReport(e);
            } catch (RuntimeException e) {
                Path stagingRoot = pluginsDir.toAbsolutePath().normalize().resolve(STAGING_DIR);
                report = new PluginTransactionRecoveryReport(List.of(recoveryFailure(
                        STAGING_DIR, stagingRoot, FailureKind.RECOVERY_FAILED,
                        "unexpected recovery failure: " + describeRecoveryFailure(e))));
            }
            if (report.safeToScan() && recoveryGate.state() == PluginRecoveryGateState.UNCHECKED) {
                recoveryGate = PluginRecoveryGateSnapshot.safe(report);
            } else {
                blockRuntimeOperations(report);
            }
            return recoveryGate.report();
        } finally {
            installLock.unlock();
        }
    }

    private PluginTransactionRecoveryReport recoverPendingTransactionsExclusive() {
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path stagingRoot = pluginsRoot.resolve(STAGING_DIR);
        List<Failure> failures = new ArrayList<>();
        try {
            assertExistingPathComponentsSafe(pluginsRoot, pluginsRoot, "plugins root");
        } catch (PluginRecoveryValidationException e) {
            failures.add(recoveryFailure(STAGING_DIR, stagingRoot, FailureKind.STAGING_ROOT_UNSAFE,
                    e.getMessage()));
            return new PluginTransactionRecoveryReport(failures);
        }
        cleanupHiddenWorkspaceRootBestEffort(pluginsRoot, PREPARATION_DIR);
        cleanupHiddenWorkspaceRootBestEffort(pluginsRoot, FINALIZATION_DIR);

        BasicFileAttributes stagingAttributes;
        try {
            stagingAttributes = readAttributesIfPresent(stagingRoot).orElse(null);
        } catch (IOException e) {
            failures.add(recoveryFailure(STAGING_DIR, stagingRoot, FailureKind.STAGING_ENUMERATION_FAILED,
                    "could not determine whether staging root exists: " + describeRecoveryFailure(e)));
            return new PluginTransactionRecoveryReport(failures);
        }
        if (stagingAttributes == null) {
            return PluginTransactionRecoveryReport.success();
        }
        if (stagingAttributes.isSymbolicLink() || stagingAttributes.isOther()
                || !stagingAttributes.isDirectory()) {
            failures.add(recoveryFailure(STAGING_DIR, stagingRoot, FailureKind.STAGING_ROOT_UNSAFE,
                    "staging root must be a plain directory"));
            return new PluginTransactionRecoveryReport(failures);
        }

        List<Path> transactions = new ArrayList<>();
        try (Stream<Path> entries = Files.list(stagingRoot)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (transactions.size() >= MAX_RECOVERY_TRANSACTIONS) {
                    failures.add(recoveryFailure(STAGING_DIR, stagingRoot,
                            FailureKind.STAGING_ENUMERATION_FAILED,
                            "staging root exceeds the supported transaction count"));
                    return new PluginTransactionRecoveryReport(failures);
                }
                transactions.add(iterator.next());
            }
            transactions.sort(Comparator.comparing(path -> path.getFileName().toString()));
        } catch (IOException | RuntimeException e) {
            failures.add(recoveryFailure(STAGING_DIR, stagingRoot, FailureKind.STAGING_ENUMERATION_FAILED,
                    describeRecoveryFailure(e)));
            return new PluginTransactionRecoveryReport(failures);
        }

        List<Path> emptyTransactions = new ArrayList<>();
        List<PluginRecoveryPlan> plans = new ArrayList<>();
        PluginRecoveryResourceBudget recoveryBudget = new PluginRecoveryResourceBudget();
        for (Path transaction : transactions) {
            String transactionId = transaction.getFileName().toString();
            try {
                recoveryBudget.requireAvailable();
                BasicFileAttributes attributes = readAttributesIfPresent(transaction).orElse(null);
                if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() || !Objects.equals(transaction.getParent(), stagingRoot)) {
                    failures.add(recoveryFailure(transactionId, transaction,
                            FailureKind.INVALID_TRANSACTION_ENTRY,
                            "staging entry must be a direct plain directory"));
                    continue;
                }
                PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction, recoveryBudget);
                if (plan == null) {
                    emptyTransactions.add(transaction);
                } else {
                    plans.add(plan);
                }
            } catch (PluginRecoveryValidationException e) {
                failures.add(recoveryFailure(transactionId, transaction, e.kind(), e.getMessage()));
                if (recoveryBudget.exhausted()) {
                    break;
                }
            } catch (IOException e) {
                failures.add(recoveryFailure(transactionId, transaction, FailureKind.RECOVERY_FAILED,
                        describeRecoveryFailure(e)));
            } catch (RuntimeException e) {
                failures.add(recoveryFailure(transactionId, transaction, FailureKind.RECOVERY_FAILED,
                        "unexpected transaction recovery failure: " + describeRecoveryFailure(e)));
            }
        }

        // 任一事务 claims 无法证明时，不得先修改其它事务可能共享的 target/origin。
        if (!failures.isEmpty()) {
            return new PluginTransactionRecoveryReport(failures);
        }

        PluginRecoveryPlanSet planSet = new PluginRecoveryPlanSet(emptyTransactions, plans);
        planSet.conflicts().forEach(conflict -> failures.add(recoveryFailure(
                conflict.plan().transactionId(),
                conflict.plan().transaction(),
                conflict.kind(),
                conflict.detail())));
        if (!failures.isEmpty()) {
            return new PluginTransactionRecoveryReport(failures);
        }
        for (Path emptyTransaction : planSet.emptyTransactions()) {
            try {
                Files.delete(emptyTransaction);
            } catch (IOException | RuntimeException e) {
                failures.add(recoveryFailure(emptyTransaction.getFileName().toString(), emptyTransaction,
                        FailureKind.RECOVERY_FAILED,
                        "could not remove empty transaction directory: " + describeRecoveryFailure(e)));
            }
        }
        List<PluginRecoveryPlan> rollbackPlans = planSet.rollbackPlans();
        List<PluginRecoveryPlan> finalPlans = planSet.finalPlans();
        for (PluginRecoveryPlan plan : rollbackPlans) {
            try {
                executeRecoveryPlan(plan, recoveryBudget);
            } catch (PluginRecoveryValidationException e) {
                failures.add(recoveryFailure(plan.transactionId(), plan.transaction(), e.kind(), e.getMessage()));
                break;
            } catch (IOException | RuntimeException e) {
                failures.add(recoveryFailure(plan.transactionId(), plan.transaction(), FailureKind.RECOVERY_FAILED,
                        describeRecoveryFailure(e)));
                break;
            }
        }

        if (failures.isEmpty() && !finalPlans.isEmpty()) {
            PluginRecoveryPlan finalPlanInProgress = null;
            try {
                // 先把全部终态的清单、摘要和目标 provenance 只读复核完，再收敛 hardlink 发布在崩溃点
                // 可能留下的双名字；不能因前一个终态先写入而掩盖后一个坏清单。
                for (PluginRecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    recoveryArtifactInspector.validateTransactionTree(plan.transaction(), plan.manifest());
                    recoveryArtifactInspector.validateState(plan.manifest(), recoveryBudget);
                    verifyFinalRecoveryBinding(plan.manifest(), recoveryBudget);
                }
                for (PluginRecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    normalizeFinalRecoveryAliases(plan.manifest(), recoveryBudget);
                }
                finalPlanInProgress = null;
                VisibleArtifactInventory inventory = planSet.requiresVisibleInventory()
                        ? recoveryVisibleInventoryVerifier.inspectVisibleInventory(recoveryBudget)
                        : VisibleArtifactInventory.empty();
                // alias 收敛后再以单份可见 inventory 做冲突复核，最后才统一退役审计清单。
                for (PluginRecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    recoveryArtifactInspector.validateTransactionTree(plan.transaction(), plan.manifest());
                    recoveryArtifactInspector.validateState(plan.manifest(), recoveryBudget);
                    verifyFinalRecoveryPlan(plan.manifest(), inventory, recoveryBudget);
                }
                for (PluginRecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    retireTransaction(plan, recoveryBudget);
                }
            } catch (PluginRecoveryValidationException e) {
                failures.add(recoveryFailure(
                        finalPlanInProgress != null ? finalPlanInProgress.transactionId() : STAGING_DIR,
                        finalPlanInProgress != null ? finalPlanInProgress.transaction() : stagingRoot,
                        e.kind(), e.getMessage()));
            } catch (IOException | RuntimeException e) {
                failures.add(recoveryFailure(
                        finalPlanInProgress != null ? finalPlanInProgress.transactionId() : STAGING_DIR,
                        finalPlanInProgress != null ? finalPlanInProgress.transaction() : stagingRoot,
                        FailureKind.RECOVERY_FAILED,
                        describeRecoveryFailure(e)));
            }
        }

        try {
            Files.deleteIfExists(stagingRoot);
        } catch (IOException | RuntimeException e) {
            if (failures.isEmpty()) {
                failures.add(recoveryFailure(STAGING_DIR, stagingRoot, FailureKind.RECOVERY_FAILED,
                        "staging root still contains an unprocessed entry or could not be removed: "
                                + describeRecoveryFailure(e)));
            }
        }
        return new PluginTransactionRecoveryReport(failures);
    }

    private void verifyFinalRecoveryPlan(RecoveryManifest manifest, VisibleArtifactInventory inventory,
                                         PluginRecoveryResourceBudget budget)
            throws PluginRecoveryValidationException {
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            return;
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            recoveryVisibleInventoryVerifier.verifyActivatedTarget(
                    manifest,
                    inventory,
                    budget,
                    verificationService);
        } else {
            recoveryVisibleInventoryVerifier.verifyRemovedIdentityAbsent(manifest, inventory);
        }
    }

    private void verifyFinalRecoveryBinding(RecoveryManifest manifest, PluginRecoveryResourceBudget budget)
            throws PluginRecoveryValidationException {
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            return;
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            recoveryVisibleInventoryVerifier.verifyActivatedTargetBinding(
                    manifest,
                    budget,
                    verificationService);
        }
    }

    /** 校验单个事务并建立只读恢复计划；返回 null 表示无 manifest 的空事务目录。 */
    private PluginRecoveryPlan prepareRecoveryPlan(Path pluginsRoot, Path transaction)
            throws IOException, PluginRecoveryValidationException {
        return prepareRecoveryPlan(pluginsRoot, transaction, null);
    }

    private PluginRecoveryPlan prepareRecoveryPlan(
            Path pluginsRoot,
            Path transaction,
            PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        String transactionId = transaction.getFileName().toString();
        Path manifestPath = PluginRecoveryManifestStore.manifestPath(transaction);
        BasicFileAttributes manifestAttributes = readAttributesIfPresent(manifestPath).orElse(null);
        if (manifestAttributes == null) {
            try (Stream<Path> entries = Files.list(transaction)) {
                if (entries.findAny().isEmpty()) {
                    return null;
                }
            }
            throw new PluginRecoveryValidationException(FailureKind.MISSING_MANIFEST,
                    "transaction directory contains files but has no recovery manifest; preserved for manual recovery");
        }
        if (manifestAttributes.isSymbolicLink() || manifestAttributes.isOther()
                || !manifestAttributes.isRegularFile()) {
            throw invalidManifest("transaction manifest must be a plain regular file");
        }
        if (PluginRecoveryManifestStore.exceedsMaximumSize(manifestAttributes.size())) {
            throw invalidManifest("transaction manifest exceeds the supported size");
        }
        beforeRecoveryManifestRead(manifestPath);
        ReadResult readManifest;
        try {
            readManifest = PluginRecoveryManifestStore.read(manifestPath);
        } catch (ReadException readFailure) {
            if (budget != null) {
                budget.consumeManifestBytes(readFailure.byteCount());
            }
            throw readFailure;
        }
        if (budget != null) {
            budget.consumeManifestBytes(readManifest.byteCount());
        }
        RecoveryManifest manifest = recoveryManifestValidator.validate(
                transaction, readManifest.properties());
        if (budget != null) {
            budget.consumeManifest(
                    manifest.backups().size(),
                    manifest.newArtifact() != null ? manifest.newArtifact().size() : 0L,
                    manifest.backups().stream().map(backup -> backup.expected().size()).toList());
            consumeRecoverySidecars(manifest, budget);
        }
        int entries = recoveryArtifactInspector.validateTransactionTree(transaction, manifest);
        if (budget != null) {
            budget.consumeEntries(entries);
        }
        recoveryArtifactInspector.validateState(
                manifest,
                budget != null ? budget : new PluginRecoveryResourceBudget());
        return new PluginRecoveryPlan(transactionId, transaction, manifest);
    }

    private void consumeRecoverySidecars(RecoveryManifest manifest, PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        Set<Path> sidecars = new LinkedHashSet<>();
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            consumeRecoverySidecar(manifest.staged(), sidecars, budget);
            consumeRecoverySidecar(manifest.target(), sidecars, budget);
        }
        for (RecoveryBackup backup : manifest.backups()) {
            consumeRecoverySidecar(backup.origin(), sidecars, budget);
            consumeRecoverySidecar(backup.backup(), sidecars, budget);
        }
    }

    private void consumeRecoverySidecar(Path artifact, Set<Path> sidecars, PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        Optional<Path> selected = provenanceStore.existingManagedSidecarPathStrict(artifact);
        if (selected.isEmpty() || !sidecars.add(selected.orElseThrow())) {
            return;
        }
        try {
            var measured = provenanceStore.measureManagedSidecarStrict(artifact);
            if (measured.isEmpty() || !measured.orElseThrow().path().equals(selected.orElseThrow())) {
                throw new IOException("recovery provenance changed while budgeting: " + selected.orElseThrow());
            }
            budget.consumeSidecarBytes(measured.orElseThrow().byteCount());
        } catch (PluginProvenanceStore.ReadBudgetExceededException budgetFailure) {
            budget.consumeSidecarBytes(budgetFailure.byteCount());
            throw budgetFailure;
        }
    }

    private void executeRecoveryPlan(PluginRecoveryPlan plan) throws IOException, PluginRecoveryValidationException {
        executeRecoveryPlan(plan, new PluginRecoveryResourceBudget());
    }

    private void executeRecoveryPlan(PluginRecoveryPlan plan, PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        RecoveryManifest manifest = plan.manifest();
        // 缩短 TOCTOU 窗口：全局预检后、第一次写入前重新核对目录树、身份、摘要与状态分布。
        recoveryArtifactInspector.validateTransactionTree(plan.transaction(), manifest);
        recoveryArtifactInspector.validateState(manifest, budget);

        if (manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)) {
            recoveryVisibleInventoryVerifier.verifyActivatedTarget(
                    manifest,
                    recoveryVisibleInventoryVerifier.inspectVisibleInventory(budget),
                    budget,
                    verificationService);
            retireTransaction(plan, budget);
            return;
        }
        if (manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED) {
            recoveryVisibleInventoryVerifier.verifyRemovedIdentityAbsent(
                    manifest,
                    recoveryVisibleInventoryVerifier.inspectVisibleInventory(budget));
            retireTransaction(plan, budget);
            return;
        }
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            retireTransaction(plan, budget);
            return;
        }

        if (manifest.state() != PluginTransactionState.ROLLING_BACK) {
            manifest = persistRecoveryState(plan, PluginTransactionState.ROLLING_BACK, budget);
            plan = new PluginRecoveryPlan(plan.transactionId(), plan.transaction(), manifest);
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            // ROLLING_BACK 明确允许 artifact/sidecar 已部分删除；每一步崩溃后都能从同一状态幂等继续。
            deleteArtifactAndSidecar(manifest.staged());
            deleteArtifactAndSidecar(manifest.target());
        }
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState observed = recoveryArtifactInspector.inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            restoreDeclaredBackup(backup, observed);
        }
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState restored = recoveryArtifactInspector.inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            recoveryArtifactInspector.requireBackupRestored(backup, restored);
        }
        RecoveryManifest rolledBack = persistRecoveryState(plan, PluginTransactionState.ROLLED_BACK, budget);
        recoveryArtifactInspector.validateTransactionTree(plan.transaction(), rolledBack);
        recoveryArtifactInspector.validateState(rolledBack, budget);
        retireTransaction(new PluginRecoveryPlan(plan.transactionId(), plan.transaction(), rolledBack), budget);
    }

    private RecoveryManifest persistRecoveryState(PluginRecoveryPlan plan, PluginTransactionState state)
            throws IOException {
        return persistRecoveryState(plan, state, new PluginRecoveryResourceBudget());
    }

    private RecoveryManifest persistRecoveryState(
            PluginRecoveryPlan plan, PluginTransactionState state, PluginRecoveryResourceBudget budget) throws IOException {
        RecoveryManifest current = plan.manifest();
        if (current.operation() == RecoveryOperation.INSTALL) {
            recoveryManifestWriter.requireInstallStateTransition(current.state(), state);
        } else {
            boolean valid = current.state() == PluginTransactionState.PREPARED
                    && state == PluginTransactionState.ROLLING_BACK
                    || current.state() == PluginTransactionState.ROLLING_BACK
                    && state == PluginTransactionState.ROLLED_BACK;
            if (!valid) {
                throw new IOException("invalid plugin removal recovery transition: "
                        + current.state() + " -> " + state);
            }
        }
        RecoveryManifest candidate = new RecoveryManifest(current.operation(), state,
                current.packageId(), current.version(), current.target(), current.staged(),
                current.newArtifact(), current.replaces(), current.backups());
        recoveryManifestWriter.persist(
                plan.transaction(),
                plan.transactionId(),
                candidate,
                "PixivDownloader plugin transaction recovery",
                budget,
                verificationService);
        return candidate;
    }

    private void retireTransaction(PluginRecoveryPlan plan) throws IOException, PluginRecoveryValidationException {
        retireTransaction(plan, new PluginRecoveryResourceBudget());
    }

    private void retireTransaction(PluginRecoveryPlan plan, PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        recoveryArtifactInspector.validateTransactionTree(plan.transaction(), plan.manifest());
        recoveryArtifactInspector.validateState(plan.manifest(), budget);
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path finalizationRoot = pluginsRoot.resolve(FINALIZATION_DIR);
        if (readAttributesIfPresent(finalizationRoot).isEmpty()) {
            Files.createDirectory(finalizationRoot);
        }
        requirePlainManagedDirectory(pluginsRoot, finalizationRoot, "transaction finalization root");
        Path retired = finalizationRoot.resolve(plan.transactionId() + "-" + UUID.randomUUID());
        try {
            Files.move(plan.transaction(), retired, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("filesystem does not support atomic transaction retirement", e);
        }
        deleteRecursivelyQuietly(retired);
        try {
            Files.deleteIfExists(finalizationRoot);
        } catch (IOException ignored) {
            // 崩溃遗留的隐藏退役目录不再位于恢复 / PF4J 扫描面。
        }
        deleteStagingRootIfEmpty();
    }

    private void restoreDeclaredBackup(RecoveryBackup backup, LogicalArtifactState observed) throws IOException {
        if (observed.artifactAt(backup.origin())) {
            if (backup.expected().hasSidecar() && !observed.sidecarAt(backup.origin())
                    && observed.sidecarAt(backup.backup())) {
                provenanceStore.moveSidecarOnly(backup.backup(), backup.origin());
            }
            return;
        }
        if (observed.artifactAt(backup.backup())) {
            if (observed.sidecarAt(backup.origin()) && !observed.sidecarAt(backup.backup())) {
                provenanceStore.moveSidecarOnly(backup.origin(), backup.backup());
            }
            moveArtifactWithSidecar(backup.backup(), backup.origin());
        }
    }

    /** 完成态只可能把 hardlink 发布的源名遗留为同 inode alias；证明同一身份后删源名，不触碰目标名。 */
    private void normalizeFinalRecoveryAliases(RecoveryManifest manifest, PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            return;
        }
        for (RecoveryBackup backup : manifest.backups()) {
            normalizeCompletedMoveAlias(backup.expected(), backup.origin(), backup.backup(), budget);
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            normalizeCompletedMoveAlias(manifest.newArtifact(), manifest.staged(), manifest.target(), budget);
        }
    }

    private void normalizeCompletedMoveAlias(ExpectedArtifact expected, Path source, Path target,
                                             PluginRecoveryResourceBudget budget)
            throws IOException, PluginRecoveryValidationException {
        LogicalArtifactState state = recoveryArtifactInspector.inspectLogicalArtifact(
                expected, source, target, budget);
        if (!state.artifactAt(target)
                || expected.hasSidecar() && !state.sidecarAt(target)) {
            throw unsafePath("completed hardlink publication is missing its target binding: " + target);
        }
        if (state.sidecarAt(source) && state.sidecarAt(target)) {
            provenanceStore.delete(source);
        }
        if (state.artifactAt(source) && state.artifactAt(target)) {
            BasicFileAttributes sourceAttributes = readAttributesIfPresent(source).orElse(null);
            if (sourceAttributes == null || sourceAttributes.isSymbolicLink() || sourceAttributes.isOther()
                    || !sourceAttributes.isRegularFile() || !Files.isSameFile(source, target)) {
                throw unsafePath("completed hardlink publication source alias is unsafe: " + source);
            }
            Files.delete(source);
        }
        LogicalArtifactState normalized = recoveryArtifactInspector.inspectLogicalArtifact(
                expected, source, target, budget);
        if (normalized.artifactAt(source) || normalized.sidecarAt(source)
                || !normalized.artifactAt(target)
                || expected.hasSidecar() && !normalized.sidecarAt(target)) {
            throw unsafePath("completed hardlink publication aliases could not be normalized: " + source);
        }
    }

    private PluginInstallResult installExclusive(Path packagePath, boolean allowDowngrade,
                                                 PluginPackageOrigin origin) {
        if (packagePath == null || !Files.isRegularFile(packagePath)) {
            return rejected(PluginInstallOutcome.REJECTED_EMPTY, "package file not found: " + packagePath);
        }
        String lower = packagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean isZip = lower.endsWith(".zip");
        if (!isZip && !lower.endsWith(".jar")) {
            return rejected(PluginInstallOutcome.REJECTED_MALFORMED,
                    "unsupported package type (expected .zip or .jar): " + packagePath.getFileName());
        }

        // 0. 资源与 entry 名安全扫描（.zip / .jar 同等），在任何解压 / 落盘前完成。
        try {
            PluginPackageVerifier.verify(packagePath, limits);
        } catch (PluginPackageException e) {
            return rejected(mapReason(e.reason()), e.getMessage());
        }

        // 1. 检视：读布局 + 包级描述符（描述符读取字节受 limits 约束）
        PluginPackageInspection inspection;
        try {
            inspection = PluginPackageReader.inspect(packagePath, limits);
        } catch (PluginPackageException e) {
            return rejected(mapReason(e.reason()), e.getMessage());
        }
        PluginDescriptor descriptor = inspection.descriptor();

        List<String> catalogMismatches = catalogDescriptorMismatches(origin, descriptor);
        if (!catalogMismatches.isEmpty()) {
            return new PluginInstallResult(PluginInstallOutcome.REJECTED_INTEGRITY, descriptor, null, null,
                    catalogMismatches);
        }

        if (origin.signature() != null && inspection.innerJarEntry() != null) {
            return rejected(PluginInstallOutcome.REJECTED_INTEGRITY,
                    "signed package must be the artifact loaded by the runtime");
        }
        VerificationResult verification = verificationService.verifyForInstall(packagePath, descriptor, origin);
        if (!verification.accepted()) {
            return rejected(PluginInstallOutcome.REJECTED_INTEGRITY,
                    "plugin package verification failed: " + verification.status());
        }

        // 2. 校验描述符内容
        List<String> validationErrors = descriptor.externalValidationErrors();
        if (!validationErrors.isEmpty()) {
            return new PluginInstallResult(PluginInstallOutcome.REJECTED_INVALID, descriptor, null, null,
                    validationErrors);
        }

        // 3. SDK 兼容门：不兼容不装为可加载状态
        if (!descriptor.isSdkCompatible()) {
            return new PluginInstallResult(PluginInstallOutcome.REJECTED_INCOMPATIBLE, descriptor, null, null,
                    List.of("requires SDK " + descriptor.requires().display()
                            + ", but core provides " + PluginPackageReader.sdkVersion()));
        }

        // 4. 对会整体物化的 .zip 再按中央目录视图校验全部 entry。
        if (isZip) {
            try {
                ZipSafety.assertSafeArchiveEntries(packagePath);
            } catch (PluginPackageException e) {
                PluginInstallOutcome outcome = e.reason() == PluginPackageException.Reason.UNSAFE
                        ? PluginInstallOutcome.REJECTED_UNSAFE
                        : mapReason(e.reason());
                return new PluginInstallResult(outcome, descriptor, null, null, List.of(e.getMessage()));
            }
        }

        // 5. 重复 / 升级 / 降级判定
        List<InstalledPlugin> installedPlugins = listInstalledExclusive();
        List<InstalledPlugin> sameId = new ArrayList<>();
        for (InstalledPlugin installed : installedPlugins) {
            if (installed.id().equals(descriptor.id())) {
                sameId.add(installed);
            }
        }
        InstalledPlugin highest = sameId.stream()
                .max(Comparator.comparing(installed -> PluginPackageVersion.parse(installed.version())))
                .orElse(null);

        PluginInstallOutcome outcome;
        String previousVersion = highest != null ? highest.version() : null;
        if (highest == null) {
            outcome = PluginInstallOutcome.INSTALLED;
        } else {
            int cmp = PluginPackageVersion.parse(descriptor.version())
                    .compareTo(PluginPackageVersion.parse(highest.version()));
            if (cmp > 0) {
                outcome = PluginInstallOutcome.UPGRADED;
            } else if (cmp == 0) {
                outcome = PluginInstallOutcome.DUPLICATE;
            } else if (allowDowngrade) {
                outcome = PluginInstallOutcome.DOWNGRADED;
            } else {
                return new PluginInstallResult(PluginInstallOutcome.DOWNGRADE_REJECTED, descriptor, null,
                        highest.version(), List.of("refusing to downgrade " + descriptor.id() + " from "
                        + highest.version() + " to " + descriptor.version() + " (force required)"));
            }
        }

        String canonicalName = canonicalFileName(descriptor, inspection.format());
        Path target = pluginsDir.resolve(canonicalName).normalize();

        // 幂等快路径：同版本且唯一现存包就是规范文件 → 原样保留，不重写
        if (outcome == PluginInstallOutcome.DUPLICATE && sameId.size() == 1
                && sameId.get(0).path().toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            VerificationResult duplicateVerification = verificationService.verifyForInstall(target, descriptor, origin);
            if (!duplicateVerification.accepted()) {
                return rejected(PluginInstallOutcome.REJECTED_INTEGRITY,
                        "installed plugin verification failed: " + duplicateVerification.status());
            }
            try {
                provenanceStore.write(target, origin, duplicateVerification);
            } catch (IOException e) {
                return new PluginInstallResult(PluginInstallOutcome.FAILED, descriptor, null,
                        previousVersion, List.of("failed to persist plugin provenance: " + e.getMessage()));
            }
            return new PluginInstallResult(PluginInstallOutcome.DUPLICATE, descriptor, sameId.get(0).path(),
                    previousVersion, List.of(descriptor.id() + " " + descriptor.version() + " already installed"));
        }

        // 6. 提交（原子、失败清暂存）
        try {
            List<InstalledPlugin> superseded = Stream.concat(sameId.stream(), installedPlugins.stream()
                    .filter(installed -> descriptor.replaces().contains(installed.id()))).distinct().toList();
            return commit(packagePath, inspection, descriptor, outcome, superseded, target, previousVersion,
                    origin);
        } catch (IOException e) {
            log.error("Failed to install plugin package {}: {}", packagePath.getFileName(), e.toString());
            return new PluginInstallResult(PluginInstallOutcome.FAILED, descriptor, null, previousVersion,
                    List.of("install failed: " + e.getMessage()));
        }
    }

    /**
     * 读出安装目录内全部可识别的插件包（{@code .jar} / {@code .zip}，跳过隐藏 / 暂存）。无法解析的包被跳过并记日志，
     * 不影响其它包。
     */
    public List<InstalledPlugin> listInstalled() {
        installLock.lock();
        try {
            if (!acquireDirectorySessionLockIfPresent()) {
                return List.of();
            }
            requireRecoverySafe("list installed plugins");
            return listInstalledExclusive();
        } finally {
            installLock.unlock();
        }
    }

    /**
     * 在同一 installer 锁域内冻结可见 artifact 与其严格 provenance 结果，供管理读模型避免
     * “旧 artifact 路径 + 新 sidecar”之类 SAFE 状态内的跨事务混合快照。
     */
    public InstalledPluginInventorySnapshot snapshotInstalledWithProvenance(
            int maximumRecords, long maximumBytes) {
        if (maximumRecords <= 0 || maximumBytes < 0L) {
            throw new IllegalArgumentException("management provenance limits are invalid");
        }
        installLock.lock();
        try {
            if (!acquireDirectorySessionLockIfPresent()) {
                return new InstalledPluginInventorySnapshot(List.of(), false);
            }
            requireRecoverySafe("snapshot installed plugins with provenance");
            List<Artifact> installed = inspectInstalledArtifactsExclusive();
            beforeManagementProvenanceSnapshot();
            return inventorySnapshotter.snapshot(installed, maximumRecords, maximumBytes);
        } finally {
            installLock.unlock();
        }
    }

    /** 以当前已安装 artifact 的精确 SHA-256 重新批准执行；全程位于安装锁与目录会话锁内。 */
    public PluginProvenanceRecord approveTrust(String pluginId, String confirmedArtifactSha256) {
        String id = requirePluginId(pluginId);
        String confirmed = requireSha256(confirmedArtifactSha256);
        installLock.lock();
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("approve plugin execution trust");
            Artifact artifact = requireSingleInstalledArtifact(id);
            PluginProvenanceRecord provenance = provenanceStore.readRequiredForRecovery(artifact.plugin().path());
            if (provenance.developmentOnly()) {
                throw new IllegalArgumentException("development-only plugins do not inherit production trust");
            }
            VerificationResult result = verificationService.verifyInstalled(
                    artifact.plugin().path(), artifact.plugin().descriptor(), provenance);
            if (!result.accepted() || !confirmed.equals(result.sha256())
                    || !artifact.artifactSha256().equals(result.sha256())) {
                throw new IllegalArgumentException("trust confirmation does not bind the installed artifact");
            }
            PluginProvenanceRecord refreshed = provenance.withOfflineResult(
                    result, artifact.plugin().id(), artifact.plugin().version());
            PluginProvenanceRecord approved = refreshed.withTrustDecision(PluginTrustPolicy.approve(
                    artifact.plugin().descriptor(), refreshed, Instant.now()));
            provenanceStore.write(artifact.plugin().path(), approved);
            return approved;
        } catch (IOException e) {
            throw new IllegalStateException("failed to approve plugin execution trust", e);
        } finally {
            installLock.unlock();
        }
    }

    /** 撤销持久化执行信任；当前 generation 不被强停，下一次 initialize/start/load/reload/startup 在插件代码前拒绝。 */
    public PluginProvenanceRecord revokeTrust(String pluginId) {
        String id = requirePluginId(pluginId);
        installLock.lock();
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("revoke plugin execution trust");
            Artifact artifact = requireSingleInstalledArtifact(id);
            PluginProvenanceRecord provenance = provenanceStore.readRequiredForRecovery(artifact.plugin().path());
            if (!artifact.artifactSha256().equals(provenance.artifactSha256())) {
                throw new IllegalStateException("installed plugin provenance does not bind current artifact");
            }
            PluginProvenanceRecord revoked = provenance.withTrustRevokedAt(Instant.now());
            provenanceStore.write(artifact.plugin().path(), revoked);
            return revoked;
        } catch (IOException e) {
            throw new IllegalStateException("failed to revoke plugin execution trust", e);
        } finally {
            installLock.unlock();
        }
    }

    private Artifact requireSingleInstalledArtifact(String pluginId) {
        List<Artifact> matches = inspectInstalledArtifactsExclusive().stream()
                .filter(artifact -> pluginId.equals(artifact.plugin().id()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("expected exactly one installed artifact for " + pluginId);
        }
        return matches.get(0);
    }

    private static String requirePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank() || !pluginId.equals(pluginId.trim())) {
            throw new IllegalArgumentException("pluginId is missing or malformed");
        }
        return pluginId;
    }

    private static String requireSha256(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("confirmedArtifactSha256 is not a SHA-256 digest");
        }
        return sha256.toLowerCase(Locale.ROOT);
    }

    private List<InstalledPlugin> listInstalledExclusive() {
        return inspectInstalledArtifactsExclusive().stream()
                .map(Artifact::plugin).toList();
    }

    private List<Artifact> inspectInstalledArtifactsExclusive() {
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        try {
            PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(pluginsRoot);
            if (!scan.rootPresent()) {
                return List.of();
            }
            assertExistingPathComponentsSafe(pluginsRoot, pluginsRoot, "plugins root");
            List<Artifact> result = new ArrayList<>(scan.candidates().size());
            PluginRecoveryResourceBudget inventoryBudget = new PluginRecoveryResourceBudget();
            for (Path path : scan.candidates()) {
                try {
                    BasicFileAttributes before = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (before.isSymbolicLink() || before.isOther() || !before.isRegularFile()
                            || before.size() <= 0L || before.size() > limits.maxArchiveBytes()) {
                        log.warn("Skipping plugin package outside the supported file shape or size: {}",
                                path.getFileName());
                        continue;
                    }
                    CleanupIdentity identity = cleanupIdentity(before);
                    String digest = PluginPackageIntegrity.sha256Hex(path);
                    PluginPackageInspection inspection = inventoryBudget.inspectArchive(path, digest, limits);
                    BasicFileAttributes after = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (after.isSymbolicLink() || after.isOther() || !after.isRegularFile()
                            || after.size() != before.size()
                            || !identity.equals(cleanupIdentity(after))) {
                        throw new IOException("installed plugin artifact changed during inventory: " + path);
                    }
                    result.add(new Artifact(
                            new InstalledPlugin(inspection.descriptor(), path), before.size(), digest));
                } catch (PluginPackageException | IOException e) {
                    log.warn("Skipping unreadable plugin package {}: {}", path.getFileName(), e.getMessage());
                }
            }
            return List.copyOf(result);
        } catch (IOException | PluginRecoveryValidationException e) {
            throw new IllegalStateException("failed to enumerate plugins directory safely", e);
        }
    }

    /** 调用方已完成物理卸载后，事务化删除指定包的全部已安装 artifact。 */
    public boolean removeInstalled(String packageId) {
        return removeInstalled(new PluginRemovalAttempt(packageId));
    }

    /** 删除并把复验后的磁盘终态写入调用方回执；任何失败重抛前回执都先完成更新。 */
    public boolean removeInstalled(PluginRemovalAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        String packageId = attempt.packageId();
        installLock.lock();
        Path unpublishedTransaction = null;
        Path transaction = null;
        boolean committedManifestPersisted = false;
        try {
            acquireDirectorySessionLockForMutation();
            requireRecoverySafe("remove installed plugin");
            List<InstalledPlugin> matches = listInstalledExclusive().stream()
                    .filter(plugin -> Objects.equals(packageId, plugin.id())).toList();
            if (matches.isEmpty()) {
                return false;
            }
            Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
            String transactionId = "remove-" + UUID.randomUUID();
            transaction = pluginsRoot.resolve(STAGING_DIR).resolve(transactionId);
            Path backupDir = transaction.resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY);
            List<Backup> backups = new ArrayList<>();
            try {
                unpublishedTransaction = createUnpublishedTransaction(pluginsRoot, transactionId);
                for (InstalledPlugin plugin : matches) {
                    Path backup = backupDir.resolve(backups.size() + "-" + plugin.path().getFileName());
                    backups.add(new Backup(plugin.path(), backup));
                }
                List<RecoveryBackup> frozenBackups = freezeRemovalBackups(null, backups, packageId);
                RecoveryManifest publishedManifest = new RecoveryManifest(
                        RecoveryOperation.REMOVE, PluginTransactionState.PREPARED,
                        packageId, "", null, null, null, List.of(), frozenBackups);
                RecoveryManifest unpublishedManifest = relocateManifestToTransaction(
                        publishedManifest, unpublishedTransaction);
                recoveryManifestWriter.persist(
                        unpublishedTransaction,
                        transactionId,
                        unpublishedManifest,
                        publishedManifest,
                        "PixivDownloader plugin removal transaction",
                        verificationService);
                beforeRemovalTransactionPublished(unpublishedTransaction);
                publishTransaction(unpublishedTransaction, transaction);
                unpublishedTransaction = null;
                afterRemovalTransactionPublished(transaction);
                validatePublishedRemovalTransaction(pluginsRoot, transaction);
                Files.createDirectories(backupDir);
                for (Backup backup : backups) {
                    moveArtifactWithSidecar(backup.origin(), backup.backup());
                }
                writeRemovalManifest(transaction, packageId, PluginTransactionState.COMMITTED, backups);
                committedManifestPersisted = true;
                afterRemovalCommittedManifestPersisted(transaction);
                PluginRecoveryPlan completed = prepareRecoveryPlan(pluginsRoot, transaction);
                if (completed == null || completed.manifest().operation() != RecoveryOperation.REMOVE
                        || completed.manifest().state() != PluginTransactionState.COMMITTED) {
                    throw new IOException("completed removal transaction could not be verified");
                }
                recoveryVisibleInventoryVerifier.verifyRemovedIdentityAbsent(completed.manifest());
                retireTransaction(completed);
                attempt.confirm(PluginRemovalAttempt.Outcome.REMOVED);
                return true;
            } catch (Throwable e) {
                Throwable terminalFailure = mergeUnpublishedCleanupFailure(unpublishedTransaction, e);
                RemovalFailureOutcome outcome;
                try {
                    outcome = recoverFailedRemovalTransaction(
                            transaction, packageId, committedManifestPersisted);
                } catch (Throwable recoveryFailure) {
                    attempt.confirm(PluginRemovalAttempt.Outcome.UNSAFE);
                    terminalFailure = mergeCompensationFailure(terminalFailure, recoveryFailure);
                    rethrowIfError(terminalFailure);
                    throw new IllegalStateException(
                            "failed to remove installed plugin " + packageId, terminalFailure);
                }
                if (outcome == RemovalFailureOutcome.REMOVED) {
                    attempt.confirm(PluginRemovalAttempt.Outcome.REMOVED);
                    log.warn("Plugin removal {} reported a failure after COMMITTED was verified; "
                            + "keeping the durable removal: {}", packageId, terminalFailure.toString());
                    rethrowIfFatal(terminalFailure);
                    return true;
                }
                attempt.confirm(outcome == RemovalFailureOutcome.ROLLED_BACK
                        ? PluginRemovalAttempt.Outcome.ROLLED_BACK
                        : PluginRemovalAttempt.Outcome.UNSAFE);
                rethrowIfError(terminalFailure);
                throw new IllegalStateException(
                        "failed to remove installed plugin " + packageId, terminalFailure);
            }
        } finally {
            installLock.unlock();
        }
    }

    private PluginInstallResult commit(Path packagePath, PluginPackageInspection inspection,
                                       PluginDescriptor descriptor, PluginInstallOutcome outcome,
                                       List<InstalledPlugin> supersededCandidates, Path target, String previousVersion,
                                       PluginPackageOrigin origin)
            throws IOException {
        Files.createDirectories(pluginsDir); // 目录创建归安装流程
        Path stagingRoot = pluginsDir.resolve(STAGING_DIR);
        Path staging = stagingRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(staging);

        // 本次安装必须让安装目录里同 id 旧包从可识别文件中消失（规范目标自身除外）——纳入提交事务、失败可回滚
        List<InstalledPlugin> superseded = supersededExcluding(supersededCandidates, target);
        Path backupDir = staging.resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY);
        List<Backup> backups = new ArrayList<>();
        List<String> removedNames = new ArrayList<>();
        boolean backupsResolved = false; // 备份已「随提交丢弃」或「随回滚还原」，可安全清理
        try {
            Path stagedArtifact = staging.resolve(target.getFileName().toString());
            produceArtifact(packagePath, inspection, stagedArtifact);
            VerificationResult stagedVerification = verificationService.verifyForInstall(
                    stagedArtifact, descriptor, origin);
            if (!stagedVerification.accepted()) {
                throw new IOException("staged plugin artifact verification failed: "
                        + stagedVerification.status());
            }
            provenanceStore.write(stagedArtifact, origin, stagedVerification);

            // 1. 把被取代旧包原子移入隔离备份；任一失败 → 回滚已隔离者、返回 FAILED，绝不放置新包
            if (!superseded.isEmpty()) {
                Files.createDirectories(backupDir);
            }
            for (InstalledPlugin old : superseded) {
                Path oldArtifact = old.path();
                Path backup = backupDir.resolve(backups.size() + "-" + oldArtifact.getFileName());
                try {
                    isolateSuperseded(oldArtifact, backup);
                } catch (IOException e) {
                    backupsResolved = restoreSuperseded(backups);
                    log.error("Aborting install of {} {}: cannot isolate superseded {}: {}",
                            descriptor.id(), descriptor.version(), oldArtifact.getFileName(), e.toString());
                    return new PluginInstallResult(PluginInstallOutcome.FAILED, descriptor, null, previousVersion,
                            List.of("install aborted: cannot remove superseded package "
                                    + oldArtifact.getFileName() + " (" + e.getMessage() + ")"));
                }
                backups.add(new Backup(oldArtifact, backup));
                removedNames.add(oldArtifact.getFileName().toString());
            }

            // 2. 放置新包到最终目标；失败 → 还原被取代旧包、返回 FAILED（尽量保持原安装状态）
            try {
                moveArtifactWithSidecar(stagedArtifact, target);
            } catch (IOException e) {
                backupsResolved = restoreSuperseded(backups);
                log.error("Failed to place plugin {} into {}: {}", descriptor.id(), target.getFileName(), e.toString());
                return new PluginInstallResult(PluginInstallOutcome.FAILED, descriptor, null, previousVersion,
                        List.of("install failed: " + e.getMessage()));
            }

            // 提交成功：隔离备份成为可丢弃（随暂存清理删除）
            backupsResolved = true;
            List<String> messages = new ArrayList<>();
            messages.add(outcome + " " + descriptor.id() + " " + descriptor.version() + " -> " + target.getFileName());
            if (!removedNames.isEmpty()) {
                messages.add("removed superseded: " + String.join(", ", removedNames));
            }
            return new PluginInstallResult(outcome, descriptor, target, previousVersion, messages);
        } finally {
            cleanupStaging(stagingRoot, staging, backupsResolved);
        }
    }

    /** 据布局把规范产物写入暂存：解压目录形态复制整 zip；单 jar 形态取出内层 jar 或复制 jar 本体。 */
    private static void produceArtifact(Path packagePath, PluginPackageInspection inspection, Path stagedArtifact)
            throws IOException {
        switch (inspection.format()) {
            case EXPLODED_DIRECTORY ->
                    Files.copy(packagePath, stagedArtifact, StandardCopyOption.REPLACE_EXISTING);
            case SINGLE_JAR -> {
                if (inspection.innerJarEntry() != null) {
                    ZipSafety.extractEntryTo(packagePath, inspection.innerJarEntry(), stagedArtifact);
                } else {
                    Files.copy(packagePath, stagedArtifact, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            default -> throw new IOException("unknown package format: " + inspection.format());
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        BasicFileAttributes sourceAttributes = readAttributesIfPresent(source).orElse(null);
        if (sourceAttributes == null || sourceAttributes.isSymbolicLink() || sourceAttributes.isOther()
                || !sourceAttributes.isRegularFile()) {
            throw new IOException("plugin artifact source is not a plain regular file: " + source);
        }
        if (readAttributesIfPresent(target).isPresent()) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        boolean linked = false;
        try {
            // WindowsFileCopy 的 ATOMIC_MOVE 分支会无条件覆盖目标。事务发布必须先用 hardlink 的
            // CREATE_NEW 语义保留 no-clobber，再删除源目录项；崩溃留下的同 inode 双名字由恢复器收敛。
            Files.createLink(target, source);
            linked = true;
            if (!Files.isSameFile(source, target)) {
                throw new IOException("plugin artifact hardlink did not preserve file identity");
            }
            Files.delete(source);
        } catch (IOException | RuntimeException e) {
            if (linked) {
                try {
                    if (readAttributesIfPresent(source).isPresent()
                            && readAttributesIfPresent(target).isPresent()
                            && Files.isSameFile(source, target)) {
                        Files.delete(target);
                    }
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private boolean restoreBackups(List<CommittedPluginTransaction.BackupArtifact> backups) {
        boolean restored = true;
        for (CommittedPluginTransaction.BackupArtifact backup : backups) {
            if (!Files.exists(backup.backup())) {
                continue;
            }
            try {
                moveArtifactWithSidecar(backup.backup(), backup.origin());
            } catch (IOException e) {
                restored = false;
                log.error("Failed to restore plugin backup {}: {}", backup.backup(), e.toString());
            }
        }
        return restored;
    }

    private void moveArtifactWithSidecar(Path source, Path target) throws IOException {
        provenanceStore.moveWithArtifact(source, target, ExternalPluginInstaller::moveIntoPlace);
    }

    private void deleteArtifactAndSidecar(Path artifact) throws IOException {
        Files.deleteIfExists(artifact);
        provenanceStore.delete(artifact);
    }

    private Path createUnpublishedTransaction(Path pluginsRoot, String transactionId) throws IOException {
        Files.createDirectories(pluginsRoot);
        requirePlainManagedDirectory(pluginsRoot, pluginsRoot, "plugins root");
        Path preparationRoot = pluginsRoot.resolve(PREPARATION_DIR);
        if (readAttributesIfPresent(preparationRoot).isEmpty()) {
            Files.createDirectory(preparationRoot);
        }
        requirePlainManagedDirectory(pluginsRoot, preparationRoot, "transaction preparation root");
        Path transaction = preparationRoot.resolve(transactionId);
        Files.createDirectory(transaction);
        requirePlainManagedDirectory(pluginsRoot, transaction, "unpublished transaction directory");
        return transaction;
    }

    private void publishTransaction(Path unpublishedTransaction, Path transaction) throws IOException {
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path stagingRoot = transaction.getParent();
        if (readAttributesIfPresent(stagingRoot).isEmpty()) {
            Files.createDirectory(stagingRoot);
        }
        requirePlainManagedDirectory(pluginsRoot, stagingRoot, "transaction staging root");
        if (readAttributesIfPresent(transaction).isPresent()) {
            throw new IOException("published transaction path already exists: " + transaction);
        }
        try {
            Files.move(unpublishedTransaction, transaction, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("filesystem does not support atomic transaction publication", e);
        }
        requirePlainManagedDirectory(pluginsRoot, transaction, "published transaction directory");
        deletePreparationRootIfEmpty();
    }

    private void requirePlainManagedDirectory(Path pluginsRoot, Path directory, String role) throws IOException {
        try {
            assertExistingPathComponentsSafe(pluginsRoot, directory, role);
        } catch (PluginRecoveryValidationException e) {
            throw new IOException(role + " is unsafe: " + e.getMessage(), e);
        }
        BasicFileAttributes attributes = readAttributesIfPresent(directory).orElse(null);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isDirectory()) {
            throw new IOException(role + " must be a plain directory: " + directory);
        }
    }

    private void deletePreparationRootIfEmpty() {
        try {
            Files.deleteIfExists(pluginsDir.toAbsolutePath().normalize().resolve(PREPARATION_DIR));
        } catch (IOException ignored) {
            // 其它未发布事务或崩溃遗留工作区不影响已发布恢复状态。
        }
    }

    private RecoveryManifest relocateManifestToTransaction(RecoveryManifest manifest, Path transaction) {
        Path staged = manifest.staged() == null ? null
                : transaction.resolve("new").resolve(manifest.staged().getFileName());
        List<RecoveryBackup> backups = new ArrayList<>(manifest.backups().size());
        for (RecoveryBackup backup : manifest.backups()) {
            backups.add(new RecoveryBackup(backup.expected(), backup.origin(),
                    transaction.resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY)
                            .resolve(backup.backup().getFileName())));
        }
        return new RecoveryManifest(manifest.operation(), manifest.state(), manifest.packageId(),
                manifest.version(), manifest.target(), staged, manifest.newArtifact(), manifest.replaces(),
                List.copyOf(backups));
    }

    private void validatePublishedTransaction(Path pluginsRoot, Path transaction) throws IOException {
        try {
            PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null || plan.manifest().operation() != RecoveryOperation.INSTALL
                    || plan.manifest().state() != PluginTransactionState.PREPARED) {
                throw new IOException("published transaction is not a valid prepared install");
            }
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("published transaction is unsafe: " + e.getMessage(), e);
        }
    }

    private void validatePublishedRemovalTransaction(Path pluginsRoot, Path transaction) throws IOException {
        try {
            PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null || plan.manifest().operation() != RecoveryOperation.REMOVE
                    || plan.manifest().state() != PluginTransactionState.PREPARED) {
                throw new IOException("published transaction is not a valid prepared removal");
            }
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("published removal transaction is unsafe: " + e.getMessage(), e);
        }
    }

    private RemovalFailureOutcome recoverFailedRemovalTransaction(
            Path transaction, String packageId, boolean committedManifestPersisted) throws Throwable {
        if (transaction == null) {
            return RemovalFailureOutcome.ROLLED_BACK;
        }
        try {
            if (readAttributesIfPresent(transaction).isEmpty()) {
                if (committedManifestPersisted) {
                    recoveryVisibleInventoryVerifier.verifyRemovedIdentityAbsent(
                            packageId,
                            recoveryVisibleInventoryVerifier.inspectVisibleInventory());
                    return RemovalFailureOutcome.REMOVED;
                }
                return RemovalFailureOutcome.ROLLED_BACK;
            }
            Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
            PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null || plan.manifest().operation() != RecoveryOperation.REMOVE) {
                throw new IOException("removal transaction is not safely recoverable");
            }
            boolean removalDurable = plan.manifest().state() == PluginTransactionState.COMMITTED;
            executeRecoveryPlan(plan);
            deleteStagingRootIfEmpty();
            return removalDurable ? RemovalFailureOutcome.REMOVED : RemovalFailureOutcome.ROLLED_BACK;
        } catch (Throwable recoveryFailure) {
            blockAfterPublishedFailure(transaction, FailureKind.RECOVERY_FAILED, recoveryFailure);
            log.error("Keeping failed plugin removal transaction {} for startup recovery: {}",
                    transaction.getFileName(), recoveryFailure.toString());
            throw recoveryFailure;
        }
    }

    private void blockAfterPublishedFailure(Path transaction, FailureKind kind, Throwable failure) {
        Path effective = transaction != null
                ? transaction.toAbsolutePath().normalize()
                : pluginsDir.toAbsolutePath().normalize().resolve(STAGING_DIR);
        String transactionId = transaction != null && transaction.getFileName() != null
                ? transaction.getFileName().toString() : STAGING_DIR;
        blockRuntimeOperations(new PluginTransactionRecoveryReport(List.of(recoveryFailure(
                transactionId, effective, kind,
                "live plugin transaction became unsafe: " + describeRecoveryFailure(failure)))));
    }

    private PublishedTransactionFailure blockPublishedTransactionIfPresent(
            Path transaction, Throwable failure) {
        if (transaction == null) {
            return new PublishedTransactionFailure(false, failure);
        }
        Throwable terminalFailure = failure;
        try {
            if (readAttributesIfPresent(transaction).isEmpty()) {
                return new PublishedTransactionFailure(false, terminalFailure);
            }
        } catch (Throwable pathFailure) {
            terminalFailure = mergeCompensationFailure(terminalFailure, pathFailure);
        }
        try {
            blockAfterPublishedFailure(transaction, FailureKind.RECOVERY_FAILED, terminalFailure);
        } catch (Throwable blockFailure) {
            terminalFailure = mergeCompensationFailure(terminalFailure, blockFailure);
        }
        return new PublishedTransactionFailure(true, terminalFailure);
    }

    private Throwable mergeUnpublishedCleanupFailure(Path unpublishedTransaction, Throwable failure) {
        try {
            deleteRecursivelyQuietly(unpublishedTransaction);
            return failure;
        } catch (Throwable cleanupFailure) {
            return mergeCompensationFailure(failure, cleanupFailure);
        }
    }

    private static void addSuppressedSafely(Throwable primary, Throwable suppressed) {
        if (primary == null || suppressed == null || primary == suppressed) {
            return;
        }
        for (Throwable existing : primary.getSuppressed()) {
            if (existing == suppressed) {
                return;
            }
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // 诊断附加失败不得覆盖原始事务故障。
        }
    }

    /**
     * 补偿链统一优先级：原始 JVM fatal、后续 JVM fatal、普通 Error、非 Error。
     * 同级保留先发失败，被替换的失败只作为最终主失败的 suppressed。
     */
    private static Throwable mergeCompensationFailure(Throwable original, Throwable subsequent) {
        if (original == null) {
            return subsequent;
        }
        if (subsequent == null || original == subsequent) {
            return original;
        }
        Throwable primary = compensationFailureSeverity(subsequent) > compensationFailureSeverity(original)
                ? subsequent : original;
        Throwable replaced = primary == original ? subsequent : original;
        addSuppressedSafely(primary, replaced);
        return primary;
    }

    private static int compensationFailureSeverity(Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
            return 2;
        }
        return failure instanceof Error ? 1 : 0;
    }

    private static void rethrowIfError(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private void writeManifest(PreparedPluginTransaction prepared, PluginTransactionState state,
                               List<CommittedPluginTransaction.BackupArtifact> backups) throws IOException {
        PluginDescriptor descriptor = prepared.result().descriptor();
        RecoveryManifest existing = requireManagedInstallManifest(prepared);
        recoveryManifestWriter.requireInstallStateTransition(existing.state(), state);

        ExpectedArtifact newArtifact = existing != null
                ? existing.newArtifact()
                : recoveryArtifactSnapshotter.snapshotInstallArtifact(
                        prepared.stagedArtifact(),
                        prepared.target(),
                        descriptor.id(),
                        descriptor.version(),
                        verificationService);
        List<RecoveryBackup> frozenBackups = recoveryArtifactSnapshotter.freezeInstallBackups(
                existing,
                backups);
        RecoveryManifest candidate = new RecoveryManifest(
                RecoveryOperation.INSTALL, state, descriptor.id(), descriptor.version(),
                prepared.target().toAbsolutePath().normalize(),
                prepared.stagedArtifact().toAbsolutePath().normalize(),
                newArtifact, List.copyOf(descriptor.replaces()), frozenBackups);
        recoveryManifestWriter.persist(
                prepared.transactionDirectory(),
                prepared.transactionId(),
                candidate,
                "PixivDownloader plugin transaction",
                verificationService);
    }

    private void writeRemovalManifest(Path transaction, String packageId, PluginTransactionState state,
                                      List<Backup> backups) throws IOException {
        RecoveryManifest existing = readExistingManifest(transaction);
        if (existing != null) {
            if (existing.operation() != RecoveryOperation.REMOVE
                    || !existing.packageId().equals(packageId)) {
                throw new IOException("plugin removal manifest identity changed");
            }
            if (existing.state() != PluginTransactionState.PREPARED
                    || state != PluginTransactionState.COMMITTED) {
                throw new IOException("invalid plugin removal state transition: "
                        + existing.state() + " -> " + state);
            }
        } else if (state != PluginTransactionState.PREPARED) {
            throw new IOException("plugin removal transaction has no PREPARED manifest to advance");
        }
        List<RecoveryBackup> frozenBackups = freezeRemovalBackups(existing, backups, packageId);
        RecoveryManifest candidate = new RecoveryManifest(
                RecoveryOperation.REMOVE, state, packageId, "", null, null,
                null, List.of(), frozenBackups);
        recoveryManifestWriter.persist(
                transaction,
                transaction.getFileName().toString(),
                candidate,
                "PixivDownloader plugin removal transaction",
                verificationService);
    }

    private RecoveryManifest readExistingManifest(Path transaction) throws IOException {
        Path manifestPath = PluginRecoveryManifestStore.manifestPath(transaction);
        BasicFileAttributes attributes = readAttributesIfPresent(manifestPath).orElse(null);
        if (attributes == null) {
            return null;
        }
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("plugin transaction manifest is not a plain regular file");
        }
        if (PluginRecoveryManifestStore.exceedsMaximumSize(attributes.size())) {
            throw new IOException("plugin transaction manifest exceeds the supported size");
        }
        try {
            return recoveryManifestValidator.validate(
                    transaction, PluginRecoveryManifestStore.read(manifestPath).properties());
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("plugin transaction manifest is invalid: " + e.getMessage(), e);
        }
    }

    private List<RecoveryBackup> freezeRemovalBackups(
            RecoveryManifest existing,
            List<Backup> backups,
            String packageId) throws IOException {
        return recoveryArtifactSnapshotter.freezeRemovalBackups(
                existing,
                backups.stream()
                        .map(backup -> new BackupPath(backup.origin(), backup.backup()))
                        .toList(),
                packageId);
    }

    private void requirePreparedMatchesManifest(PreparedPluginTransaction prepared, RecoveryManifest manifest)
            throws IOException {
        PluginDescriptor descriptor = prepared.result() != null ? prepared.result().descriptor() : null;
        List<Path> expectedCurrent = prepared.expectedCurrentArtifacts().stream()
                .map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        List<Path> manifestCurrent = manifest.backups().stream()
                .map(RecoveryBackup::origin).sorted().toList();
        if (descriptor == null || manifest.operation() != RecoveryOperation.INSTALL
                || !descriptor.id().equals(manifest.packageId())
                || !descriptor.version().equals(manifest.version())
                || !List.copyOf(descriptor.replaces()).equals(manifest.replaces())
                || !prepared.target().toAbsolutePath().normalize().equals(manifest.target())
                || !prepared.stagedArtifact().toAbsolutePath().normalize().equals(manifest.staged())
                || !expectedCurrent.equals(manifestCurrent)) {
            throw new IOException("prepared plugin transaction does not match its frozen recovery manifest");
        }
    }

    private RecoveryManifest requireManagedInstallManifest(
            PreparedPluginTransaction prepared,
            PluginTransactionState... expectedStates) throws IOException {
        if (prepared == null || !prepared.readyToCommit() || prepared.transactionDirectory() == null) {
            throw new IOException("plugin transaction handle is incomplete");
        }
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path transaction;
        try {
            transaction = requirePreparedTransactionPath(pluginsRoot, prepared);
            PluginRecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null) {
                throw new IOException("plugin transaction has no recovery manifest");
            }
            RecoveryManifest manifest = plan.manifest();
            requirePreparedMatchesManifest(prepared, manifest);
            if (expectedStates != null && expectedStates.length > 0
                    && java.util.Arrays.stream(expectedStates).noneMatch(state -> state == manifest.state())) {
                throw new IOException("plugin transaction is in unexpected state: " + manifest.state());
            }
            return manifest;
        } catch (PluginRecoveryValidationException e) {
            throw new IOException("plugin transaction handle is not managed safely: " + e.getMessage(), e);
        }
    }

    private RecoveryManifest requireManagedCommittedManifest(
            CommittedPluginTransaction transaction,
            PluginTransactionState... expectedStates) throws IOException {
        if (transaction == null || transaction.prepared() == null || transaction.backups() == null) {
            throw new IOException("committed plugin transaction handle is incomplete");
        }
        RecoveryManifest manifest = requireManagedInstallManifest(transaction.prepared(), expectedStates);
        if (manifest.backups().size() != transaction.backups().size()) {
            throw new IOException("committed plugin transaction backup set does not match its manifest");
        }
        for (int i = 0; i < manifest.backups().size(); i++) {
            RecoveryBackup frozen = manifest.backups().get(i);
            CommittedPluginTransaction.BackupArtifact supplied = transaction.backups().get(i);
            if (!frozen.origin().equals(supplied.origin().toAbsolutePath().normalize())
                    || !frozen.backup().equals(supplied.backup().toAbsolutePath().normalize())) {
                throw new IOException("committed plugin transaction backup paths do not match its manifest");
            }
        }
        return manifest;
    }

    private void recoverFailedInstallTransaction(PreparedPluginTransaction prepared) throws Throwable {
        try {
            RecoveryManifest manifest = requireManagedInstallManifest(prepared,
                    PluginTransactionState.PREPARED,
                    PluginTransactionState.OLD_ISOLATED,
                    PluginTransactionState.NEW_PLACED);
            executeRecoveryPlan(new PluginRecoveryPlan(prepared.transactionId(),
                    prepared.transactionDirectory(), manifest));
            deleteStagingRootIfEmpty();
        } catch (Throwable recoveryFailure) {
            confirmPreparedUnsafe(prepared);
            blockAfterPublishedFailure(prepared != null ? prepared.transactionDirectory() : null,
                    FailureKind.RECOVERY_FAILED, recoveryFailure);
            log.error("Keeping failed plugin transaction {} for startup recovery: {}",
                    prepared != null ? prepared.transactionId() : "unknown", recoveryFailure.toString());
            throw recoveryFailure;
        }
    }

    private static void confirmPreparedUnsafe(PreparedPluginTransaction prepared) {
        if (prepared != null && prepared.commitState() == PreparedPluginTransaction.CommitState.PREPARED) {
            prepared.confirmCommitState(PreparedPluginTransaction.CommitState.UNSAFE);
        }
    }

    private Path requirePreparedTransactionPath(Path pluginsRoot, PreparedPluginTransaction prepared)
            throws PluginRecoveryValidationException {
        String transactionId = Objects.requireNonNull(prepared.transactionId(), "transactionId");
        if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw unsafePath("prepared transaction id is not a safe token");
        }
        Path transaction = prepared.transactionDirectory().toAbsolutePath().normalize();
        Path stagingRoot = pluginsRoot.resolve(STAGING_DIR);
        if (!transaction.equals(stagingRoot.resolve(transactionId))
                || !Objects.equals(transaction.getParent(), stagingRoot)) {
            throw unsafePath("prepared transaction directory is outside the managed staging root");
        }
        assertExistingPathComponentsSafe(pluginsRoot, transaction, "prepared transaction directory");
        return transaction;
    }

    private static void requirePathWithin(Path path, Path expectedRoot, String role)
            throws PluginRecoveryValidationException {
        Path normalizedRoot = expectedRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw unsafePath(role + " escapes its expected root: " + path);
        }
    }

    private static void assertExistingPathComponentsSafe(Path pluginsRoot, Path path, String role)
            throws PluginRecoveryValidationException {
        Path normalizedRoot = pluginsRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        requirePathWithin(normalizedPath, normalizedRoot, role);
        Path current = normalizedRoot.getRoot();
        for (Path component : normalizedRoot) {
            current = current == null ? component : current.resolve(component);
            assertPlainExistingComponent(current, role);
        }
        current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(component);
            if (!assertPlainExistingComponent(current, role)) {
                break;
            }
        }
    }

    private static boolean assertPlainExistingComponent(Path path, String role)
            throws PluginRecoveryValidationException {
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(path).orElse(null);
            if (attributes == null) {
                return false;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw unsafePath(role + " traverses a symbolic link or reparse/special entry: " + path);
            }
            return true;
        } catch (IOException e) {
            throw unsafePath(role + " path component could not be inspected: " + describeRecoveryFailure(e));
        }
    }

    /** 只有明确的 NoSuchFileException 才代表不存在；ACL / I/O 错误必须向上失败。 */
    private static java.util.Optional<BasicFileAttributes> readAttributesIfPresent(Path path) throws IOException {
        try {
            return java.util.Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return java.util.Optional.empty();
        }
    }

    private static Failure recoveryFailure(String transactionId, Path transaction, FailureKind kind, String detail) {
        return new Failure(transactionId, transaction, kind, detail);
    }

    private static PluginRecoveryValidationException invalidManifest(String message) {
        return new PluginRecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }

    private static PluginRecoveryValidationException unsafePath(String message) {
        return new PluginRecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }

    private static String describeRecoveryFailure(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    /**
     * 未发布准备区和已退役清理区都不是权威恢复面；无法安全删除时原样保留并告警，不能因此绕过或封闭后续
     * {@code .staging} 校验。artifact scanner 会继续忽略这些隐藏路径。
     */
    private void cleanupHiddenWorkspaceRootBestEffort(Path pluginsRoot, String directoryName) {
        try {
            recoveryWorkspaceCleaner.cleanup(pluginsRoot, directoryName);
        } catch (IOException | PluginRecoveryValidationException | RuntimeException e) {
            log.warn("Leaving non-authoritative plugin transaction workspace {} after cleanup failure: {}",
                    pluginsRoot.resolve(directoryName), describeRecoveryFailure(e));
        }
    }

    private void deleteStagingRootIfEmpty() {
        try {
            Files.deleteIfExists(pluginsDir.resolve(STAGING_DIR));
        } catch (IOException ignored) {
            // 非空表示还有其它事务。
        }
    }

    /** 同 id 旧包中需要被本次安装清除的那些（规范目标自身除外）。 */
    private static List<InstalledPlugin> supersededExcluding(List<InstalledPlugin> sameId, Path target) {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        List<InstalledPlugin> result = new ArrayList<>();
        for (InstalledPlugin old : sameId) {
            if (!old.path().toAbsolutePath().normalize().equals(normalizedTarget)) {
                result.add(old);
            }
        }
        return result;
    }

    /**
     * 把一个被取代的旧同 id 包以同卷 hardlink 的 CREATE_NEW 语义发布到隔离备份，再删除原目录项。包内可见的接缝：
     * 测试可覆盖它来模拟「旧包无法移除 / 隔离」的 IO 失败，生产实现不放大对外 API。
     */
    void isolateSuperseded(Path origin, Path backup) throws IOException {
        moveArtifactWithSidecar(origin, backup);
    }

    /**
     * 回滚：把已隔离的旧包从备份移回原位。全部成功返回 {@code true}；任一失败记录并返回 {@code false}
     * （此时备份仍是该旧包的唯一副本，不可在清理时删除）。
     */
    private boolean restoreSuperseded(List<Backup> backups) {
        boolean allRestored = true;
        for (Backup backup : backups) {
            if (!Files.exists(backup.backup())) {
                continue;
            }
            try {
                moveArtifactWithSidecar(backup.backup(), backup.origin());
            } catch (IOException e) {
                allRestored = false;
                log.error("Failed to restore superseded plugin {} from backup {}: {}",
                        backup.origin().getFileName(), backup.backup(), e.toString());
            }
        }
        return allRestored;
    }

    /**
     * 清理本次安装的暂存目录。{@code backupsResolved} 为 {@code true}（提交成功 → 备份可丢弃，或回滚已全部还原）时
     * 递归删除整个暂存；否则保留 {@code removed/} 里未能还原的旧包备份待人工恢复，不递归删除（避免删掉旧包唯一副本）。
     */
    private void cleanupStaging(Path stagingRoot, Path staging, boolean backupsResolved) {
        if (backupsResolved) {
            deleteRecursivelyQuietly(staging);
        } else {
            log.error("Leaving staging backups for manual recovery (could not restore superseded packages): {}",
                    staging.resolve(PluginRecoveryManifestValidator.BACKUP_SUBDIRECTORY));
        }
        // 暂存根若已空则一并清掉（best-effort，不影响并发的其它安装——本设计为单管理员串行操作）
        try {
            Files.deleteIfExists(stagingRoot);
        } catch (IOException ignored) {
            // 非空或被占用：留待下次清理，不致命
        }
    }

    /** 一次被隔离的旧包：原位置 + 隔离备份位置（用于提交成功后丢弃或回滚还原）。 */
    private record Backup(Path origin, Path backup) {
    }

    private enum RemovalFailureOutcome {
        ROLLED_BACK,
        REMOVED,
        UNSAFE
    }

    private record PublishedTransactionFailure(boolean publishedOrUncertain, Throwable failure) {
    }

    @Override
    public void close() {
        if (directorySessionLock == null) {
            return;
        }
        installLock.lock();
        try {
            directorySessionLock.close();
        } catch (IOException e) {
            throw new IllegalStateException("failed to release the plugin directory session lock", e);
        } finally {
            installLock.unlock();
        }
    }

    private static String canonicalFileName(PluginDescriptor descriptor, PluginPackageFormat format) {
        String ext = format == PluginPackageFormat.SINGLE_JAR ? ".jar" : ".zip";
        return descriptor.id() + "-" + descriptor.version() + ext;
    }

    private void deleteRecursivelyQuietly(Path root) {
        if (root == null) {
            return;
        }
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try {
            if (normalizedRoot.equals(pluginsRoot) || !normalizedRoot.startsWith(pluginsRoot)) {
                throw new IOException("cleanup root is outside the managed plugins directory: " + normalizedRoot);
            }
            assertExistingPathComponentsSafe(pluginsRoot, normalizedRoot, "managed cleanup root");
            BasicFileAttributes pluginsRootAttributes = readAttributesIfPresent(pluginsRoot).orElse(null);
            BasicFileAttributes cleanupRootAttributes = readAttributesIfPresent(normalizedRoot).orElse(null);
            if (cleanupRootAttributes == null) {
                return;
            }
            if (pluginsRootAttributes == null || pluginsRootAttributes.isSymbolicLink()
                    || pluginsRootAttributes.isOther() || !pluginsRootAttributes.isDirectory()) {
                throw new IOException("plugins root changed before managed cleanup: " + pluginsRoot);
            }
            if (cleanupRootAttributes.isSymbolicLink() || cleanupRootAttributes.isOther()
                    || !cleanupRootAttributes.isDirectory()) {
                throw new IOException("managed cleanup root is not a plain directory: " + normalizedRoot);
            }
            CleanupIdentity pluginsRootIdentity = cleanupIdentity(pluginsRootAttributes);
            CleanupIdentity cleanupRootIdentity = cleanupIdentity(cleanupRootAttributes);
            beforeManagedCleanup(normalizedRoot);
            List<CleanupEntry> deletionOrder = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(normalizedRoot)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    if (deletionOrder.size() >= MAX_MANAGED_CLEANUP_ENTRIES) {
                        throw new IOException("managed cleanup tree exceeds the supported entry count");
                    }
                    Path entry = iterator.next().toAbsolutePath().normalize();
                    if (!entry.startsWith(normalizedRoot)) {
                        throw new IOException("managed cleanup traversal escaped its root: " + entry);
                    }
                    BasicFileAttributes attributes = Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()
                            || !attributes.isDirectory() && !attributes.isRegularFile()) {
                        throw new IOException("managed cleanup tree contains an unsafe entry: " + entry);
                    }
                    deletionOrder.add(new CleanupEntry(entry,
                            cleanupIdentity(attributes), attributes.isDirectory()));
                }
            }
            deletionOrder.sort(Comparator.comparingInt(
                    (CleanupEntry entry) -> entry.path().getNameCount()).reversed());
            for (CleanupEntry entry : deletionOrder) {
                requireCleanupIdentity(pluginsRoot, pluginsRootIdentity, true);
                requireCleanupIdentity(normalizedRoot, cleanupRootIdentity, true);
                BasicFileAttributes current = readAttributesIfPresent(entry.path()).orElse(null);
                if (current == null) {
                    continue;
                }
                CleanupIdentity currentIdentity = cleanupIdentity(current);
                if (!entry.identity().equals(currentIdentity)
                        || entry.directory() != current.isDirectory()
                        || current.isSymbolicLink() || current.isOther()
                        || !current.isDirectory() && !current.isRegularFile()) {
                    throw new IOException("managed cleanup entry changed after validation: " + entry.path());
                }
                Files.delete(entry.path());
            }
        } catch (IOException | PluginRecoveryValidationException | RuntimeException e) {
            log.warn("Failed to clean managed plugin directory {}: {}", normalizedRoot, e.toString());
        }
    }

    /** 包级测试接缝：模拟目录遍历在终态退役后抛出未检查异常。 */
    void beforeManagedCleanup(Path root) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟安装事务发布后、首次复验前的未检查异常。 */
    void afterInstallTransactionPublished(Path transaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟安装事务发布前的未检查异常。 */
    void beforeInstallTransactionPublished(Path unpublishedTransaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟删除事务发布后、首次复验前的未检查异常。 */
    void afterRemovalTransactionPublished(Path transaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟删除事务发布前的未检查异常。 */
    void beforeRemovalTransactionPublished(Path unpublishedTransaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟旧 artifact 已隔离且状态清单落盘后的未检查异常。 */
    void afterOldArtifactsIsolated(Path transaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟新 artifact 已放置且状态清单落盘后的未检查异常。 */
    void afterNewArtifactPlaced(Path transaction) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：证明累计预算熔断后不会继续打开后续恢复清单。 */
    void beforeRecoveryManifestRead(Path manifest) {
        // 生产实现无动作。
    }

    /** 包级测试接缝：证明管理快照在 artifact 枚举与 provenance 读取之间持续持有 installer 锁。 */
    void beforeManagementProvenanceSnapshot() {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟 ACTIVATED 清单落盘前的 I/O 失败。 */
    void beforeActivationManifestPersisted(Path transaction) throws IOException {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟 ACTIVATED 原子清单已落盘后的 I/O 报错。 */
    void afterActivationManifestPersisted(Path transaction) throws IOException {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟 COMMITTED 原子清单已落盘后的 I/O 报错。 */
    void afterCommittedManifestPersisted(Path transaction) throws IOException {
        // 生产实现无动作。
    }

    /** 包级测试接缝：模拟 REMOVE/COMMITTED 原子清单已落盘后的 I/O 报错。 */
    void afterRemovalCommittedManifestPersisted(Path transaction) throws IOException {
        // 生产实现无动作。
    }

    private static CleanupIdentity cleanupIdentity(BasicFileAttributes attributes) {
        return new CleanupIdentity(attributes.fileKey(), attributes.creationTime());
    }

    private static void requireCleanupIdentity(Path path, CleanupIdentity expectedIdentity, boolean directory)
            throws IOException {
        BasicFileAttributes attributes = readAttributesIfPresent(path).orElse(null);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || directory != attributes.isDirectory()
                || !directory && !attributes.isRegularFile()
                || !expectedIdentity.equals(cleanupIdentity(attributes))) {
            throw new IOException("managed cleanup path identity changed: " + path);
        }
    }

    private record CleanupIdentity(Object fileKey, java.nio.file.attribute.FileTime creationTime) {

        private CleanupIdentity {
            creationTime = Objects.requireNonNull(creationTime, "creationTime");
        }
    }

    private record CleanupEntry(Path path, CleanupIdentity identity, boolean directory) {
    }

    private static List<String> catalogDescriptorMismatches(PluginPackageOrigin origin,
                                                            PluginDescriptor descriptor) {
        if (origin == null || origin.source() != PluginPackageSource.MARKET_CATALOG) return List.of();
        List<String> errors = new ArrayList<>();
        if (origin.expectedPluginId() != null && !origin.expectedPluginId().equals(descriptor.id())) {
            errors.add("catalog plugin id does not match the frozen package descriptor");
        }
        if (origin.expectedVersion() != null && !origin.expectedVersion().equals(descriptor.version())) {
            errors.add("catalog version does not match the frozen package descriptor");
        }
        if (origin.expectedRequiredSdk() != null && !requirementBinding(
                VersionRequirement.parse(origin.expectedRequiredSdk())).equals(requirementBinding(descriptor.requires()))) {
            errors.add("catalog SDK requirement does not match the frozen package descriptor");
        }
        if (origin.expectedDependencies() != null
                && !dependencyBindings(origin.expectedDependencies()).equals(
                descriptorDependencyBindings(descriptor.dependencies()))) {
            errors.add("catalog dependencies do not match the frozen package descriptor");
        }
        return List.copyOf(errors);
    }

    private static Set<String> dependencyBindings(List<String> dependencies) {
        List<PluginDependencyRef> parsed = new ArrayList<>();
        dependencies.forEach(raw -> parsed.addAll(PluginDependencyRef.parseList(raw)));
        return descriptorDependencyBindings(parsed);
    }

    private static Set<String> descriptorDependencyBindings(List<PluginDependencyRef> dependencies) {
        Set<String> bindings = new HashSet<>();
        for (PluginDependencyRef dependency : dependencies) {
            bindings.add(dependency.pluginId() + '|' + requirementBinding(dependency.requirement())
                    + '|' + dependency.optional());
        }
        return bindings;
    }

    private static String requirementBinding(VersionRequirement requirement) {
        return requirement.present() + ":" + requirement.valid() + ":"
                + requirement.major() + ":" + requirement.minor();
    }

    private static PluginInstallOutcome mapReason(PluginPackageException.Reason reason) {
        return switch (reason) {
            case EMPTY -> PluginInstallOutcome.REJECTED_EMPTY;
            case MALFORMED -> PluginInstallOutcome.REJECTED_MALFORMED;
            case NO_DESCRIPTOR -> PluginInstallOutcome.REJECTED_NO_DESCRIPTOR;
            case AMBIGUOUS -> PluginInstallOutcome.REJECTED_AMBIGUOUS;
            case UNSAFE -> PluginInstallOutcome.REJECTED_UNSAFE;
            case TOO_LARGE -> PluginInstallOutcome.REJECTED_TOO_LARGE;
        };
    }

    private static PluginInstallResult rejected(PluginInstallOutcome outcome, String message) {
        return new PluginInstallResult(outcome, null, null, null, List.of(Objects.toString(message, "")));
    }

    private static Function<PluginPackageOrigin, PluginSupplyChainVerifier> fixedVerifier(
            PluginSupplyChainVerifier verifier) {
        PluginSupplyChainVerifier fixed = Objects.requireNonNull(verifier, "verifier");
        return origin -> fixed;
    }
}
