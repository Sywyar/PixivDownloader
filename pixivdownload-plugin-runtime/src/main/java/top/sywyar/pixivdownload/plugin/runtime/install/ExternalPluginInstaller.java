package top.sywyar.pixivdownload.plugin.runtime.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Properties;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
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
 *   <li><b>核心 API 兼容门</b>：{@code requires} 不被当前核心满足 → {@code REJECTED_INCOMPATIBLE}（不装为可加载状态）。</li>
 *   <li><b>Zip Slip 校验</b>：对 {@code .zip} 包做 {@link ZipSafety#assertNoTraversal}，含越界 entry → {@code REJECTED_UNSAFE}。</li>
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

    /** 暂存目录内存放「被取代旧包隔离备份」的子目录名。 */
    private static final String BACKUP_SUBDIR = "removed";

    private final Path pluginsDir;
    private final PluginPackageLimits limits;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private PluginArtifactVerificationService verificationService;
    private final PluginProvenanceStore provenanceStore;
    private final PluginDirectorySessionLock directorySessionLock;
    /** 把恢复、权威枚举与文件事务串行化（同一实例 / 同一安装目录的并发操作互斥）。 */
    private final ReentrantLock installLock = new ReentrantLock();

    private static final String TRANSACTION_MANIFEST = "transaction.properties";

    /** 当前未发布事务清单的唯一受支持格式；不对开发期旧草稿做兼容读取。 */
    private static final String TRANSACTION_FORMAT_VERSION = "1";

    /** 启动期最多枚举的待恢复事务数；超过即在任何事务写入前 fail-closed。 */
    private static final int MAX_RECOVERY_TRANSACTIONS = 256;

    /** 防止损坏清单用超大 backup.count 放大启动期开销。 */
    private static final int MAX_RECOVERY_BACKUPS = 256;

    /** 清单只承载路径、身份和摘要；超过 512 KiB 视为损坏，读取前即拒绝。 */
    private static final long MAX_RECOVERY_MANIFEST_BYTES = 512L * 1024L;

    /** 一轮启动恢复的累计预算；单事务有界不能替代全局有界。 */
    private static final long MAX_RECOVERY_TOTAL_MANIFEST_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_RECOVERY_TOTAL_BACKUPS = 1_024;
    private static final int MAX_RECOVERY_TOTAL_ENTRIES = 8_192;
    private static final long MAX_RECOVERY_TOTAL_ARTIFACT_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_RECOVERY_TOTAL_SIDECAR_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_RECOVERY_TOTAL_ARCHIVE_ENTRIES = 32_000;
    private static final long MAX_RECOVERY_TOTAL_UNCOMPRESSED_BYTES = 384L * 1024L * 1024L;
    private static final int MAX_HIDDEN_WORKSPACES = 256;
    private static final int MAX_HIDDEN_WORKSPACE_ENTRIES = 8_192;
    private static final int MAX_MANAGED_CLEANUP_ENTRIES = 8_192;

    /** 单事务允许出现的文件系统 entry 上限，覆盖 manifest/new/removed 与每份 artifact/sidecar。 */
    private static final int MAX_RECOVERY_TRANSACTION_ENTRIES = MAX_RECOVERY_BACKUPS * 4 + 16;

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
                new PluginDirectorySessionLock(Objects.requireNonNull(pluginsDir, "pluginsDir")), false);
    }

    /** bootstrap 会话使用的构造入口；目录锁由同一会话持有到进程 / context 生命周期结束。 */
    public ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                   Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                   PluginDirectorySessionLock directorySessionLock) {
        this(pluginsDir, limits, verifierResolver,
                Objects.requireNonNull(directorySessionLock, "directorySessionLock"), false);
    }

    private ExternalPluginInstaller(Path pluginsDir, PluginPackageLimits limits,
                                    Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
                                    PluginDirectorySessionLock directorySessionLock,
                                    boolean isolatedWithoutDirectoryLock) {
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
        this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
        this.provenanceStore = new PluginProvenanceStore(this.pluginsDir);
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
            this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
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
        Path stagingRoot = pluginsDir.toAbsolutePath().normalize().resolve(STAGING_DIR);
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
            ExternalPluginInstaller validator = new ExternalPluginInstaller(
                    validationDir, limits, verifierResolver, null, true);
            PluginTransactionRecoveryReport validationRecovery = validator.recoverPendingTransactions();
            if (!validationRecovery.safeToScan()) {
                throw new IOException("isolated validation directory did not pass recovery safety checks");
            }
            PluginInstallResult validated = validator.installIsolated(packagePath,
                    origin != null ? origin : PluginPackageOrigin.localUpload());
            if (!validated.accepted() || validated.descriptor() == null || validated.installedPath() == null) {
                deleteRecursivelyQuietly(unpublishedTransaction);
                return new PreparedPluginTransaction(transactionId, validated, null, null, null, List.of());
            }

            PluginDescriptor descriptor = validated.descriptor();
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
                    deleteRecursivelyQuietly(unpublishedTransaction);
                    PluginInstallResult duplicate = new PluginInstallResult(PluginInstallOutcome.DUPLICATE,
                            descriptor, highest.path(), previousVersion,
                            List.of(descriptor.id() + " " + descriptor.version() + " already installed"));
                    return new PreparedPluginTransaction(transactionId, duplicate, null, null, null,
                            sameId.stream().map(InstalledPlugin::path).toList());
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

            Files.createDirectories(unpublishedTransaction.resolve("new"));
            Path unpublishedArtifact = unpublishedTransaction.resolve("new")
                    .resolve(validated.installedPath().getFileName());
            PluginProvenanceRecord validatedProvenance = validator.provenanceStore
                    .readRequiredForRecovery(validated.installedPath());
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
            Path backupDir = transaction.resolve(BACKUP_SUBDIR);
            for (Path currentArtifact : expectedCurrent) {
                declaredBackups.add(new CommittedPluginTransaction.BackupArtifact(currentArtifact,
                        backupDir.resolve(declaredBackups.size() + "-" + currentArtifact.getFileName())));
            }
            ExpectedArtifact newArtifact = snapshotExpectedArtifact(unpublishedArtifact, unpublishedArtifact,
                    descriptor.id(), descriptor.version(), true);
            List<RecoveryBackup> frozenBackups = freezeInstallBackups(null, declaredBackups);
            RecoveryManifest publishedManifest = new RecoveryManifest(
                    RecoveryOperation.INSTALL, PluginTransactionState.PREPARED,
                    descriptor.id(), descriptor.version(), target, staged, newArtifact,
                    List.copyOf(descriptor.replaces()), frozenBackups);
            RecoveryManifest unpublishedManifest = relocateManifestToTransaction(
                    publishedManifest, unpublishedTransaction);
            validateCandidateBeforePersist(unpublishedTransaction, unpublishedManifest);
            persistManifest(unpublishedTransaction,
                    manifestProperties(transactionId, publishedManifest),
                    "PixivDownloader plugin transaction");
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
            Path backupDir = prepared.transactionDirectory().resolve(BACKUP_SUBDIR);
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
            verifyActivatedTarget(manifest);
        } catch (IOException | RecoveryValidationException e) {
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
            validateTransactionTree(prepared.transactionDirectory(), manifest);
            validateRecoveryState(manifest);
        } catch (RecoveryValidationException e) {
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
                retireTransaction(new RecoveryPlan(transaction.prepared().transactionId(),
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
            verifyActivatedTarget(durable);
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
            throws IOException, RecoveryValidationException {
        RecoveryManifest manifest = requireManagedCommittedManifest(
                transaction, PluginTransactionState.COMMITTED);
        retireTransaction(new RecoveryPlan(transaction.prepared().transactionId(),
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
                verifyActivatedTarget(durable);
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
            RecoveryPlan plan = new RecoveryPlan(transaction.prepared().transactionId(),
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

    /**
     * 调用方无法确认当前 generation 已物理清退时，不得在存活进程内继续回滚磁盘文件。保留
     * {@code NEW_PLACED} 恢复清单、封闭本进程后续插件写入与加载，并把实际回滚留给下次启动扫描前恢复。
     */
    public void deferRollbackUntilRestart(CommittedPluginTransaction transaction, Throwable failure) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(failure, "failure");
        installLock.lock();
        try {
            if (transaction.durableState() != CommittedPluginTransaction.DurableState.NEW_PLACED) {
                throw new IllegalStateException("only a NEW_PLACED plugin transaction can defer rollback");
            }
            transaction.markRecoveryBlocked();
            blockAfterPublishedFailure(transaction.prepared().transactionDirectory(),
                    FailureKind.RECOVERY_FAILED, failure);
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
                RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
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
        } catch (RecoveryValidationException e) {
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
        List<RecoveryPlan> plans = new ArrayList<>();
        RecoveryBudget recoveryBudget = new RecoveryBudget();
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
                RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction, recoveryBudget);
                if (plan == null) {
                    emptyTransactions.add(transaction);
                } else {
                    plans.add(plan);
                }
            } catch (RecoveryValidationException e) {
                failures.add(recoveryFailure(transactionId, transaction, e.kind, e.getMessage()));
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

        Set<String> conflictedTransactions = findCrossTransactionConflicts(plans, failures);
        if (!failures.isEmpty()) {
            return new PluginTransactionRecoveryReport(failures);
        }
        for (Path emptyTransaction : emptyTransactions) {
            try {
                Files.delete(emptyTransaction);
            } catch (IOException | RuntimeException e) {
                failures.add(recoveryFailure(emptyTransaction.getFileName().toString(), emptyTransaction,
                        FailureKind.RECOVERY_FAILED,
                        "could not remove empty transaction directory: " + describeRecoveryFailure(e)));
            }
        }
        List<RecoveryPlan> rollbackPlans = plans.stream()
                .filter(plan -> !isFinalRecoveryPlan(plan.manifest()))
                .toList();
        List<RecoveryPlan> finalPlans = plans.stream()
                .filter(plan -> isFinalRecoveryPlan(plan.manifest()))
                .toList();
        for (RecoveryPlan plan : rollbackPlans) {
            if (conflictedTransactions.contains(plan.transactionId())) {
                continue;
            }
            try {
                executeRecoveryPlan(plan, recoveryBudget);
            } catch (RecoveryValidationException e) {
                failures.add(recoveryFailure(plan.transactionId(), plan.transaction(), e.kind, e.getMessage()));
                break;
            } catch (IOException | RuntimeException e) {
                failures.add(recoveryFailure(plan.transactionId(), plan.transaction(), FailureKind.RECOVERY_FAILED,
                        describeRecoveryFailure(e)));
                break;
            }
        }

        if (failures.isEmpty() && !finalPlans.isEmpty()) {
            RecoveryPlan finalPlanInProgress = null;
            try {
                // 先把全部终态的清单、摘要和目标 provenance 只读复核完，再收敛 hardlink 发布在崩溃点
                // 可能留下的双名字；不能因前一个终态先写入而掩盖后一个坏清单。
                for (RecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    validateTransactionTree(plan.transaction(), plan.manifest());
                    validateRecoveryState(plan.manifest(), recoveryBudget);
                    verifyFinalRecoveryBinding(plan.manifest(), recoveryBudget);
                }
                for (RecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    normalizeFinalRecoveryAliases(plan.manifest(), recoveryBudget);
                }
                finalPlanInProgress = null;
                VisibleArtifactInventory inventory = finalPlans.stream()
                        .anyMatch(plan -> requiresVisibleInventory(plan.manifest()))
                        ? inspectVisibleArtifactInventory(recoveryBudget)
                        : VisibleArtifactInventory.empty();
                // alias 收敛后再以单份可见 inventory 做冲突复核，最后才统一退役审计清单。
                for (RecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    validateTransactionTree(plan.transaction(), plan.manifest());
                    validateRecoveryState(plan.manifest(), recoveryBudget);
                    verifyFinalRecoveryPlan(plan.manifest(), inventory, recoveryBudget);
                }
                for (RecoveryPlan plan : finalPlans) {
                    finalPlanInProgress = plan;
                    retireTransaction(plan, recoveryBudget);
                }
            } catch (RecoveryValidationException e) {
                failures.add(recoveryFailure(
                        finalPlanInProgress != null ? finalPlanInProgress.transactionId() : STAGING_DIR,
                        finalPlanInProgress != null ? finalPlanInProgress.transaction() : stagingRoot,
                        e.kind, e.getMessage()));
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

    private static boolean isFinalRecoveryPlan(RecoveryManifest manifest) {
        return manifest.state() == PluginTransactionState.ROLLED_BACK
                || manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)
                || manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED;
    }

    private static boolean requiresVisibleInventory(RecoveryManifest manifest) {
        return manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)
                || manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED;
    }

    private void verifyFinalRecoveryPlan(RecoveryManifest manifest, VisibleArtifactInventory inventory,
                                         RecoveryBudget budget)
            throws RecoveryValidationException {
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            return;
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            verifyActivatedTarget(manifest, inventory, budget);
        } else {
            verifyRemovedIdentityAbsent(manifest, inventory);
        }
    }

    private void verifyFinalRecoveryBinding(RecoveryManifest manifest, RecoveryBudget budget)
            throws RecoveryValidationException {
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            return;
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            verifyActivatedTargetBinding(manifest, budget);
        }
    }

    /** 校验单个事务并建立只读恢复计划；返回 null 表示无 manifest 的空事务目录。 */
    private RecoveryPlan prepareRecoveryPlan(Path pluginsRoot, Path transaction)
            throws IOException, RecoveryValidationException {
        return prepareRecoveryPlan(pluginsRoot, transaction, null);
    }

    private RecoveryPlan prepareRecoveryPlan(Path pluginsRoot, Path transaction, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
        String transactionId = transaction.getFileName().toString();
        Path manifestPath = transaction.resolve(TRANSACTION_MANIFEST);
        BasicFileAttributes manifestAttributes = readAttributesIfPresent(manifestPath).orElse(null);
        if (manifestAttributes == null) {
            try (Stream<Path> entries = Files.list(transaction)) {
                if (entries.findAny().isEmpty()) {
                    return null;
                }
            }
            throw new RecoveryValidationException(FailureKind.MISSING_MANIFEST,
                    "transaction directory contains files but has no recovery manifest; preserved for manual recovery");
        }
        if (manifestAttributes.isSymbolicLink() || manifestAttributes.isOther()
                || !manifestAttributes.isRegularFile()) {
            throw invalidManifest("transaction manifest must be a plain regular file");
        }
        if (manifestAttributes.size() > MAX_RECOVERY_MANIFEST_BYTES) {
            throw invalidManifest("transaction manifest exceeds the supported size");
        }
        beforeRecoveryManifestRead(manifestPath);
        ReadManifest readManifest;
        try {
            readManifest = readManifest(manifestPath);
        } catch (ManifestReadException readFailure) {
            if (budget != null) {
                budget.consumeManifestBytes(readFailure.byteCount());
            }
            throw readFailure;
        }
        if (budget != null) {
            budget.consumeManifestBytes(readManifest.byteCount());
        }
        RecoveryManifest manifest = validateRecoveryManifest(
                pluginsRoot, transaction, readManifest.properties());
        if (budget != null) {
            budget.consumeManifest(manifest);
            consumeRecoverySidecars(manifest, budget);
        }
        int entries = validateTransactionTree(transaction, manifest);
        if (budget != null) {
            budget.consumeEntries(entries);
        }
        validateRecoveryState(manifest, budget != null ? budget : new RecoveryBudget());
        return new RecoveryPlan(transactionId, transaction, manifest);
    }

    private void consumeRecoverySidecars(RecoveryManifest manifest, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
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

    private void consumeRecoverySidecar(Path artifact, Set<Path> sidecars, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
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

    /** 在任何事务发生写入前，拒绝两个事务声称同一 target/staged/origin/backup 的交叉恢复。 */
    private static Set<String> findCrossTransactionConflicts(List<RecoveryPlan> plans, List<Failure> failures) {
        java.util.Map<Path, List<RecoveryPlan>> owners = new java.util.LinkedHashMap<>();
        for (RecoveryPlan plan : plans) {
            for (Path claim : plan.manifest().claimedArtifactPaths()) {
                owners.computeIfAbsent(claim, ignored -> new ArrayList<>()).add(plan);
            }
        }
        Set<String> conflicted = new LinkedHashSet<>();
        for (var entry : owners.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String ids = entry.getValue().stream().map(RecoveryPlan::transactionId).distinct()
                    .reduce((left, right) -> left + "," + right).orElse("");
            for (RecoveryPlan plan : entry.getValue()) {
                if (conflicted.add(plan.transactionId())) {
                    failures.add(recoveryFailure(plan.transactionId(), plan.transaction(),
                            FailureKind.UNSAFE_PATH,
                            "artifact path is claimed by multiple transactions (" + ids + "): " + entry.getKey()));
                }
            }
        }
        java.util.Map<String, List<RecoveryPlan>> identityOwners = new java.util.LinkedHashMap<>();
        for (RecoveryPlan plan : plans) {
            Set<String> identities = new LinkedHashSet<>();
            identities.add(plan.manifest().packageId());
            identities.addAll(plan.manifest().replaces());
            for (RecoveryBackup backup : plan.manifest().backups()) {
                identities.add(backup.expected().pluginId());
            }
            for (String identity : identities) {
                identityOwners.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(plan);
            }
        }
        for (var entry : identityOwners.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String ids = entry.getValue().stream().map(RecoveryPlan::transactionId).distinct()
                    .reduce((left, right) -> left + "," + right).orElse("");
            for (RecoveryPlan plan : entry.getValue()) {
                if (conflicted.add(plan.transactionId())) {
                    failures.add(recoveryFailure(plan.transactionId(), plan.transaction(),
                            FailureKind.IDENTITY_CONFLICT,
                            "plugin identity is claimed by multiple transactions (" + ids + "): "
                                    + entry.getKey()));
                }
            }
        }
        return Set.copyOf(conflicted);
    }

    private void executeRecoveryPlan(RecoveryPlan plan) throws IOException, RecoveryValidationException {
        executeRecoveryPlan(plan, new RecoveryBudget());
    }

    private void executeRecoveryPlan(RecoveryPlan plan, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
        RecoveryManifest manifest = plan.manifest();
        // 缩短 TOCTOU 窗口：全局预检后、第一次写入前重新核对目录树、身份、摘要与状态分布。
        validateTransactionTree(plan.transaction(), manifest);
        validateRecoveryState(manifest, budget);

        if (manifest.operation() == RecoveryOperation.INSTALL
                && (manifest.state() == PluginTransactionState.ACTIVATED
                || manifest.state() == PluginTransactionState.COMMITTED)) {
            verifyActivatedTarget(manifest, inspectVisibleArtifactInventory(budget), budget);
            retireTransaction(plan, budget);
            return;
        }
        if (manifest.operation() == RecoveryOperation.REMOVE
                && manifest.state() == PluginTransactionState.COMMITTED) {
            verifyRemovedIdentityAbsent(manifest, inspectVisibleArtifactInventory(budget));
            retireTransaction(plan, budget);
            return;
        }
        if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
            retireTransaction(plan, budget);
            return;
        }

        if (manifest.state() != PluginTransactionState.ROLLING_BACK) {
            manifest = persistRecoveryState(plan, PluginTransactionState.ROLLING_BACK, budget);
            plan = new RecoveryPlan(plan.transactionId(), plan.transaction(), manifest);
        }
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            // ROLLING_BACK 明确允许 artifact/sidecar 已部分删除；每一步崩溃后都能从同一状态幂等继续。
            deleteArtifactAndSidecar(manifest.staged());
            deleteArtifactAndSidecar(manifest.target());
        }
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState observed = inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            restoreDeclaredBackup(backup, observed);
        }
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState restored = inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            requireBackupRestored(backup, restored);
        }
        RecoveryManifest rolledBack = persistRecoveryState(plan, PluginTransactionState.ROLLED_BACK, budget);
        validateTransactionTree(plan.transaction(), rolledBack);
        validateRecoveryState(rolledBack, budget);
        retireTransaction(new RecoveryPlan(plan.transactionId(), plan.transaction(), rolledBack), budget);
    }

    private RecoveryManifest persistRecoveryState(RecoveryPlan plan, PluginTransactionState state)
            throws IOException {
        return persistRecoveryState(plan, state, new RecoveryBudget());
    }

    private RecoveryManifest persistRecoveryState(
            RecoveryPlan plan, PluginTransactionState state, RecoveryBudget budget) throws IOException {
        RecoveryManifest current = plan.manifest();
        if (current.operation() == RecoveryOperation.INSTALL) {
            requireInstallStateTransition(current.state(), state);
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
        validateCandidateBeforePersist(plan.transaction(), candidate, budget);
        persistManifest(plan.transaction(), manifestProperties(plan.transactionId(), candidate),
                "PixivDownloader plugin transaction recovery");
        return candidate;
    }

    private void retireTransaction(RecoveryPlan plan) throws IOException, RecoveryValidationException {
        retireTransaction(plan, new RecoveryBudget());
    }

    private void retireTransaction(RecoveryPlan plan, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
        validateTransactionTree(plan.transaction(), plan.manifest());
        validateRecoveryState(plan.manifest(), budget);
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
    private void normalizeFinalRecoveryAliases(RecoveryManifest manifest, RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
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
                                             RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
        LogicalArtifactState state = inspectLogicalArtifact(expected, source, target, budget);
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
        LogicalArtifactState normalized = inspectLogicalArtifact(expected, source, target, budget);
        if (normalized.artifactAt(source) || normalized.sidecarAt(source)
                || !normalized.artifactAt(target)
                || expected.hasSidecar() && !normalized.sidecarAt(target)) {
            throw unsafePath("completed hardlink publication aliases could not be normalized: " + source);
        }
    }

    private void verifyActivatedTarget(RecoveryManifest manifest) throws RecoveryValidationException {
        try {
            RecoveryBudget budget = new RecoveryBudget();
            verifyActivatedTarget(manifest, inspectVisibleArtifactInventory(budget), budget);
        } catch (IOException e) {
            throw unsafePath("visible plugin inventory could not be proven: " + describeRecoveryFailure(e));
        }
    }

    private void verifyActivatedTarget(RecoveryManifest manifest, VisibleArtifactInventory inventory)
            throws RecoveryValidationException {
        verifyActivatedTarget(manifest, inventory, new RecoveryBudget());
    }

    private void verifyActivatedTarget(RecoveryManifest manifest, VisibleArtifactInventory inventory,
                                       RecoveryBudget budget)
            throws RecoveryValidationException {
        verifyActivatedTargetBinding(manifest, budget);
        verifyNoConflictingVisibleArtifacts(manifest, inventory);
    }

    private void verifyActivatedTargetBinding(RecoveryManifest manifest)
            throws RecoveryValidationException {
        verifyActivatedTargetBinding(manifest, new RecoveryBudget());
    }

    private void verifyActivatedTargetBinding(RecoveryManifest manifest, RecoveryBudget budget)
            throws RecoveryValidationException {
        try {
            PluginPackageInspection inspection = inspectBoundArtifact(
                    manifest.target(), manifest.newArtifact(), "activated target", budget);
            if (!List.copyOf(inspection.descriptor().replaces()).equals(manifest.replaces())) {
                throw unsafePath("activated target replaces declaration does not match its recovery manifest");
            }
            var provenance = provenanceStore.readRequiredForRecovery(manifest.target());
            VerificationResult result = verificationService.verifyInstalled(
                    manifest.target(), inspection.descriptor(), provenance);
            if (!result.accepted()) {
                throw unsafePath("activated target provenance verification failed: " + result.status());
            }
        } catch (IOException | RuntimeException e) {
            throw unsafePath("activated target provenance could not be proven: " + describeRecoveryFailure(e));
        }
    }

    private void verifyNoConflictingVisibleArtifacts(RecoveryManifest manifest,
                                                     VisibleArtifactInventory inventory)
            throws RecoveryValidationException {
        Set<String> affectedIds = new LinkedHashSet<>(manifest.replaces());
        affectedIds.add(manifest.packageId());
        boolean targetSeen = inventory.artifacts().stream()
                .anyMatch(candidate -> candidate.path().equals(manifest.target()));
        for (VisibleArtifact candidate : inventory.artifacts()) {
            if (candidate.path().equals(manifest.target())) {
                continue;
            }
            if (affectedIds.contains(candidate.descriptor().id())) {
                throw unsafePath("activated transaction leaves another visible artifact for identity "
                        + candidate.descriptor().id() + ": " + candidate.path());
            }
        }
        if (!targetSeen) {
            throw unsafePath("activated target is not the unique visible package artifact");
        }
    }

    private void verifyRemovedIdentityAbsent(RecoveryManifest manifest, VisibleArtifactInventory inventory)
            throws RecoveryValidationException {
        verifyRemovedIdentityAbsent(manifest.packageId(), inventory);
    }

    private void verifyRemovedIdentityAbsent(String packageId, VisibleArtifactInventory inventory)
            throws RecoveryValidationException {
        boolean stillVisible = inventory.artifacts().stream()
                .anyMatch(plugin -> packageId.equals(plugin.descriptor().id()));
        if (stillVisible) {
            throw unsafePath("completed removal leaves a visible artifact for identity "
                    + packageId);
        }
    }

    private void verifyRemovedIdentityAbsent(RecoveryManifest manifest) throws RecoveryValidationException {
        try {
            verifyRemovedIdentityAbsent(manifest, inspectVisibleArtifactInventory());
        } catch (IOException e) {
            throw unsafePath("visible plugin inventory could not be proven: " + describeRecoveryFailure(e));
        }
    }

    private VisibleArtifactInventory inspectVisibleArtifactInventory()
            throws IOException, RecoveryValidationException {
        return inspectVisibleArtifactInventory(new RecoveryBudget());
    }

    private VisibleArtifactInventory inspectVisibleArtifactInventory(RecoveryBudget budget)
            throws IOException, RecoveryValidationException {
        PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(
                pluginsDir.toAbsolutePath().normalize());
        List<VisibleArtifact> artifacts = new ArrayList<>(scan.candidates().size());
        for (Path candidate : scan.candidates()) {
            try {
                String digest = PluginPackageIntegrity.sha256Hex(candidate);
                PluginPackageInspection inspection = budget.inspectArchive(candidate, digest, limits);
                if (!inspection.descriptor().externalValidationErrors().isEmpty()) {
                    throw unsafePath("visible plugin artifact has an invalid identity: " + candidate);
                }
                artifacts.add(new VisibleArtifact(candidate, inspection.descriptor()));
            } catch (PluginPackageException e) {
                throw unsafePath("visible plugin artifact could not be inspected before scan: "
                        + candidate + " (" + describeRecoveryFailure(e) + ")");
            }
        }
        return new VisibleArtifactInventory(artifacts);
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

        // 0. 资源规模安全扫描（.zip / .jar 同等），在任何解压 / 落盘前——防 Zip Bomb / 解压资源耗尽。
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

        if (origin.source() == PluginPackageSource.MARKET_CATALOG && origin.signature() != null
                && inspection.innerJarEntry() != null) {
            return rejected(PluginInstallOutcome.REJECTED_INTEGRITY,
                    "signed catalog package must be the artifact loaded by the runtime");
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

        // 3. 核心 API 兼容门：不兼容不装为可加载状态
        if (!descriptor.isApiCompatible()) {
            return new PluginInstallResult(PluginInstallOutcome.REJECTED_INCOMPATIBLE, descriptor, null, null,
                    List.of("requires core API " + descriptor.requires().display()
                            + ", but core provides " + PluginPackageReader.coreApiVersion()));
        }

        // 4. Zip Slip 校验（仅 .zip：runtime 物化为 PF4J 目录前必须先拒绝越界 entry）
        if (isZip) {
            try {
                ZipSafety.assertNoTraversal(packagePath);
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

    private List<InstalledPlugin> listInstalledExclusive() {
        Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
        try {
            PluginArtifactScanner.ScanResult scan = PluginArtifactScanner.scan(pluginsRoot);
            if (!scan.rootPresent()) {
                return List.of();
            }
            assertExistingPathComponentsSafe(pluginsRoot, pluginsRoot, "plugins root");
            List<InstalledPlugin> result = new ArrayList<>(scan.candidates().size());
            for (Path path : scan.candidates()) {
                try {
                    PluginPackageInspection inspection = PluginPackageReader.inspect(path, limits);
                    result.add(new InstalledPlugin(inspection.descriptor(), path));
                } catch (PluginPackageException e) {
                    log.warn("Skipping unreadable plugin package {}: {}", path.getFileName(), e.getMessage());
                }
            }
            return List.copyOf(result);
        } catch (IOException | RecoveryValidationException e) {
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
            Path backupDir = transaction.resolve(BACKUP_SUBDIR);
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
                validateCandidateBeforePersist(unpublishedTransaction, unpublishedManifest);
                persistManifest(unpublishedTransaction,
                        manifestProperties(transactionId, publishedManifest),
                        "PixivDownloader plugin removal transaction");
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
                RecoveryPlan completed = prepareRecoveryPlan(pluginsRoot, transaction);
                if (completed == null || completed.manifest().operation() != RecoveryOperation.REMOVE
                        || completed.manifest().state() != PluginTransactionState.COMMITTED) {
                    throw new IOException("completed removal transaction could not be verified");
                }
                verifyRemovedIdentityAbsent(completed.manifest());
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
        Path backupDir = staging.resolve(BACKUP_SUBDIR);
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
        } catch (RecoveryValidationException e) {
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
                    transaction.resolve(BACKUP_SUBDIR).resolve(backup.backup().getFileName())));
        }
        return new RecoveryManifest(manifest.operation(), manifest.state(), manifest.packageId(),
                manifest.version(), manifest.target(), staged, manifest.newArtifact(), manifest.replaces(),
                List.copyOf(backups));
    }

    private void validatePublishedTransaction(Path pluginsRoot, Path transaction) throws IOException {
        try {
            RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null || plan.manifest().operation() != RecoveryOperation.INSTALL
                    || plan.manifest().state() != PluginTransactionState.PREPARED) {
                throw new IOException("published transaction is not a valid prepared install");
            }
        } catch (RecoveryValidationException e) {
            throw new IOException("published transaction is unsafe: " + e.getMessage(), e);
        }
    }

    private void validatePublishedRemovalTransaction(Path pluginsRoot, Path transaction) throws IOException {
        try {
            RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
            if (plan == null || plan.manifest().operation() != RecoveryOperation.REMOVE
                    || plan.manifest().state() != PluginTransactionState.PREPARED) {
                throw new IOException("published transaction is not a valid prepared removal");
            }
        } catch (RecoveryValidationException e) {
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
                    verifyRemovedIdentityAbsent(packageId, inspectVisibleArtifactInventory());
                    return RemovalFailureOutcome.REMOVED;
                }
                return RemovalFailureOutcome.ROLLED_BACK;
            }
            Path pluginsRoot = pluginsDir.toAbsolutePath().normalize();
            RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
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

    private static Properties manifestProperties(String transactionId, RecoveryManifest manifest) {
        Properties properties = new Properties();
        properties.setProperty("format.version", TRANSACTION_FORMAT_VERSION);
        properties.setProperty("transaction.id", transactionId);
        properties.setProperty("operation", manifest.operation().name());
        properties.setProperty("state", manifest.state().name());
        properties.setProperty("package.id", manifest.packageId());
        properties.setProperty("version", Objects.toString(manifest.version(), ""));
        properties.setProperty("target", manifest.target() != null ? manifest.target().toString() : "");
        properties.setProperty("staged", manifest.staged() != null ? manifest.staged().toString() : "");
        if (manifest.newArtifact() != null) {
            writeExpectedArtifact(properties, "artifact", manifest.newArtifact());
        } else {
            properties.setProperty("artifact.id", "");
            properties.setProperty("artifact.version", "");
            properties.setProperty("artifact.size", "");
            properties.setProperty("artifact.sha256", "");
            properties.setProperty("artifact.sidecar.sha256", "");
        }
        properties.setProperty("replaces.count", Integer.toString(manifest.replaces().size()));
        for (int i = 0; i < manifest.replaces().size(); i++) {
            properties.setProperty("replaces." + i, manifest.replaces().get(i));
        }
        properties.setProperty("backup.count", Integer.toString(manifest.backups().size()));
        for (int i = 0; i < manifest.backups().size(); i++) {
            RecoveryBackup backup = manifest.backups().get(i);
            writeExpectedArtifact(properties, "backup." + i, backup.expected());
            properties.setProperty("backup." + i + ".origin", backup.origin().toString());
            properties.setProperty("backup." + i + ".path", backup.backup().toString());
        }
        return properties;
    }

    private void writeManifest(PreparedPluginTransaction prepared, PluginTransactionState state,
                               List<CommittedPluginTransaction.BackupArtifact> backups) throws IOException {
        PluginDescriptor descriptor = prepared.result().descriptor();
        RecoveryManifest existing = requireManagedInstallManifest(prepared);
        requireInstallStateTransition(existing.state(), state);

        ExpectedArtifact newArtifact = existing != null
                ? existing.newArtifact()
                : snapshotExpectedArtifact(prepared.stagedArtifact(), prepared.target(),
                descriptor.id(), descriptor.version(), true);
        List<RecoveryBackup> frozenBackups = freezeInstallBackups(existing, backups);
        RecoveryManifest candidate = new RecoveryManifest(
                RecoveryOperation.INSTALL, state, descriptor.id(), descriptor.version(),
                prepared.target().toAbsolutePath().normalize(),
                prepared.stagedArtifact().toAbsolutePath().normalize(),
                newArtifact, List.copyOf(descriptor.replaces()), frozenBackups);
        validateCandidateBeforePersist(prepared.transactionDirectory(), candidate);

        Properties properties = new Properties();
        properties.setProperty("format.version", TRANSACTION_FORMAT_VERSION);
        properties.setProperty("transaction.id", prepared.transactionId());
        properties.setProperty("operation", RecoveryOperation.INSTALL.name());
        properties.setProperty("state", state.name());
        properties.setProperty("package.id", descriptor.id());
        properties.setProperty("version", descriptor.version());
        properties.setProperty("target", prepared.target().toAbsolutePath().normalize().toString());
        properties.setProperty("staged", prepared.stagedArtifact().toAbsolutePath().normalize().toString());
        writeExpectedArtifact(properties, "artifact", newArtifact);
        properties.setProperty("replaces.count", Integer.toString(descriptor.replaces().size()));
        for (int i = 0; i < descriptor.replaces().size(); i++) {
            properties.setProperty("replaces." + i, descriptor.replaces().get(i));
        }
        properties.setProperty("backup.count", Integer.toString(frozenBackups.size()));
        for (int i = 0; i < frozenBackups.size(); i++) {
            RecoveryBackup backup = frozenBackups.get(i);
            writeExpectedArtifact(properties, "backup." + i, backup.expected());
            properties.setProperty("backup." + i + ".origin",
                    backup.origin().toString());
            properties.setProperty("backup." + i + ".path",
                    backup.backup().toString());
        }
        persistManifest(prepared.transactionDirectory(), properties,
                "PixivDownloader plugin transaction");
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
        validateCandidateBeforePersist(transaction, candidate);

        Properties properties = new Properties();
        properties.setProperty("format.version", TRANSACTION_FORMAT_VERSION);
        properties.setProperty("transaction.id", transaction.getFileName().toString());
        properties.setProperty("operation", RecoveryOperation.REMOVE.name());
        properties.setProperty("state", state.name());
        properties.setProperty("package.id", Objects.toString(packageId, ""));
        properties.setProperty("version", "");
        properties.setProperty("target", "");
        properties.setProperty("staged", "");
        properties.setProperty("artifact.id", "");
        properties.setProperty("artifact.version", "");
        properties.setProperty("artifact.size", "");
        properties.setProperty("artifact.sha256", "");
        properties.setProperty("artifact.sidecar.sha256", "");
        properties.setProperty("replaces.count", "0");
        properties.setProperty("backup.count", Integer.toString(frozenBackups.size()));
        for (int i = 0; i < frozenBackups.size(); i++) {
            RecoveryBackup backup = frozenBackups.get(i);
            writeExpectedArtifact(properties, "backup." + i, backup.expected());
            properties.setProperty("backup." + i + ".origin",
                    backup.origin().toString());
            properties.setProperty("backup." + i + ".path",
                    backup.backup().toString());
        }
        persistManifest(transaction, properties, "PixivDownloader plugin removal transaction");
    }

    private RecoveryManifest readExistingManifest(Path transaction) throws IOException {
        Path manifestPath = transaction.resolve(TRANSACTION_MANIFEST);
        BasicFileAttributes attributes = readAttributesIfPresent(manifestPath).orElse(null);
        if (attributes == null) {
            return null;
        }
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("plugin transaction manifest is not a plain regular file");
        }
        if (attributes.size() > MAX_RECOVERY_MANIFEST_BYTES) {
            throw new IOException("plugin transaction manifest exceeds the supported size");
        }
        try {
            return validateRecoveryManifest(pluginsDir.toAbsolutePath().normalize(),
                    transaction.toAbsolutePath().normalize(), readManifest(manifestPath).properties());
        } catch (RecoveryValidationException e) {
            throw new IOException("plugin transaction manifest is invalid: " + e.getMessage(), e);
        }
    }

    private List<RecoveryBackup> freezeInstallBackups(
            RecoveryManifest existing,
            List<CommittedPluginTransaction.BackupArtifact> backups) throws IOException {
        List<RecoveryBackup> frozen = existing != null ? existing.backups() : List.of();
        if (!frozen.isEmpty()) {
            if (frozen.size() != backups.size()) {
                throw new IOException("plugin transaction backup set changed after it was frozen");
            }
            for (int i = 0; i < frozen.size(); i++) {
                Path origin = backups.get(i).origin().toAbsolutePath().normalize();
                Path backup = backups.get(i).backup().toAbsolutePath().normalize();
                if (!frozen.get(i).origin().equals(origin) || !frozen.get(i).backup().equals(backup)) {
                    throw new IOException("plugin transaction backup paths changed after they were frozen");
                }
            }
            return frozen;
        }
        List<RecoveryBackup> result = new ArrayList<>(backups.size());
        for (CommittedPluginTransaction.BackupArtifact backup : backups) {
            Path origin = backup.origin().toAbsolutePath().normalize();
            Path target = backup.backup().toAbsolutePath().normalize();
            result.add(new RecoveryBackup(
                    snapshotExpectedArtifact(origin, target, null, null, false), origin, target));
        }
        return List.copyOf(result);
    }

    private List<RecoveryBackup> freezeRemovalBackups(
            RecoveryManifest existing,
            List<Backup> backups,
            String packageId) throws IOException {
        List<RecoveryBackup> frozen = existing != null ? existing.backups() : List.of();
        if (!frozen.isEmpty()) {
            if (frozen.size() != backups.size()) {
                throw new IOException("plugin removal backup set changed after it was frozen");
            }
            for (int i = 0; i < frozen.size(); i++) {
                Path origin = backups.get(i).origin().toAbsolutePath().normalize();
                Path backup = backups.get(i).backup().toAbsolutePath().normalize();
                if (!frozen.get(i).origin().equals(origin) || !frozen.get(i).backup().equals(backup)) {
                    throw new IOException("plugin removal backup paths changed after they were frozen");
                }
            }
            return frozen;
        }
        List<RecoveryBackup> result = new ArrayList<>(backups.size());
        for (Backup backup : backups) {
            Path origin = backup.origin().toAbsolutePath().normalize();
            Path target = backup.backup().toAbsolutePath().normalize();
            result.add(new RecoveryBackup(
                    snapshotExpectedArtifact(origin, target, packageId, null, false), origin, target));
        }
        return List.copyOf(result);
    }

    private void requireInstallStateTransition(PluginTransactionState current, PluginTransactionState next)
            throws IOException {
        boolean valid = current == PluginTransactionState.PREPARED
                && (next == PluginTransactionState.PREPARED
                || next == PluginTransactionState.OLD_ISOLATED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.OLD_ISOLATED
                && (next == PluginTransactionState.NEW_PLACED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.NEW_PLACED
                && (next == PluginTransactionState.ACTIVATED
                || next == PluginTransactionState.ROLLING_BACK)
                || current == PluginTransactionState.ROLLING_BACK
                && next == PluginTransactionState.ROLLED_BACK
                || current == PluginTransactionState.ACTIVATED && next == PluginTransactionState.COMMITTED;
        if (!valid) {
            throw new IOException("invalid plugin transaction state transition: " + current + " -> " + next);
        }
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
            RecoveryPlan plan = prepareRecoveryPlan(pluginsRoot, transaction);
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
        } catch (RecoveryValidationException e) {
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
            executeRecoveryPlan(new RecoveryPlan(prepared.transactionId(),
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
            throws RecoveryValidationException {
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

    private void validateCandidateBeforePersist(Path transaction, RecoveryManifest candidate) throws IOException {
        validateCandidateBeforePersist(transaction, candidate, new RecoveryBudget());
    }

    private void validateCandidateBeforePersist(
            Path transaction, RecoveryManifest candidate, RecoveryBudget budget) throws IOException {
        try {
            if (candidate.replaces().size() > MAX_RECOVERY_BACKUPS
                    || candidate.backups().size() > MAX_RECOVERY_BACKUPS) {
                throw invalidManifest("generated transaction exceeds the supported replacement or backup count");
            }
            validateTransactionTree(transaction, candidate);
            validateRecoveryState(candidate, budget);
            if (candidate.operation() == RecoveryOperation.INSTALL
                    && (candidate.state() == PluginTransactionState.ACTIVATED
                    || candidate.state() == PluginTransactionState.COMMITTED)) {
                verifyActivatedTarget(candidate, inspectVisibleArtifactInventory(budget), budget);
            } else if (candidate.operation() == RecoveryOperation.REMOVE
                    && candidate.state() == PluginTransactionState.COMMITTED) {
                verifyRemovedIdentityAbsent(candidate, inspectVisibleArtifactInventory(budget));
            }
        } catch (RecoveryValidationException e) {
            throw new IOException("plugin transaction state is unsafe: " + e.getMessage(), e);
        }
    }

    private static void persistManifest(Path transaction, Properties properties, String comment) throws IOException {
        byte[] serialized = serializeManifest(properties, comment);
        BasicFileAttributes transactionAttributes = readAttributesIfPresent(transaction).orElse(null);
        if (transactionAttributes == null || transactionAttributes.isSymbolicLink()
                || transactionAttributes.isOther() || !transactionAttributes.isDirectory()) {
            throw new IOException("plugin transaction directory is not a plain directory");
        }
        Path manifest = transaction.resolve(TRANSACTION_MANIFEST);
        Path temporary = transaction.resolve(TRANSACTION_MANIFEST + ".tmp");
        BasicFileAttributes temporaryAttributes = readAttributesIfPresent(temporary).orElse(null);
        if (temporaryAttributes != null) {
            if (temporaryAttributes.isSymbolicLink() || temporaryAttributes.isOther()
                    || !temporaryAttributes.isRegularFile()) {
                throw new IOException("plugin transaction manifest temporary path is unsafe");
            }
            Files.delete(temporary);
        }
        BasicFileAttributes manifestAttributes = readAttributesIfPresent(manifest).orElse(null);
        if (manifestAttributes != null && (manifestAttributes.isSymbolicLink() || manifestAttributes.isOther()
                || !manifestAttributes.isRegularFile())) {
            throw new IOException("plugin transaction manifest path is unsafe");
        }
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(temporary, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(serialized);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        }
        try {
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(temporary);
            throw new IOException("filesystem does not support atomic transaction manifest persistence", e);
        }
    }

    private static byte[] serializeManifest(Properties properties, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int count;

            @Override
            public void write(int value) throws IOException {
                requireCapacity(1);
                bytes.write(value);
                count++;
            }

            @Override
            public void write(byte[] source, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, source.length);
                requireCapacity(length);
                bytes.write(source, offset, length);
                count += length;
            }

            private void requireCapacity(int increment) throws IOException {
                if (increment < 0 || count > MAX_RECOVERY_MANIFEST_BYTES - increment) {
                    throw new IOException("generated transaction manifest exceeds the supported size");
                }
            }
        };
        try (Writer writer = new OutputStreamWriter(bounded, StandardCharsets.UTF_8)) {
            properties.store(writer, comment);
        }
        return bytes.toByteArray();
    }

    private ExpectedArtifact snapshotExpectedArtifact(Path first, Path second, String expectedId,
                                                      String expectedVersion, boolean requireSidecar)
            throws IOException {
        Path artifact = existingPlainFile(first).orElse(null);
        if (artifact == null) {
            artifact = existingPlainFile(second).orElse(null);
        }
        if (artifact == null) {
            throw new IOException("transaction artifact is missing while writing recovery manifest: " + first);
        }
        try {
            PluginPackageVerifier.verify(artifact, limits);
            PluginPackageInspection inspection = PluginPackageReader.inspect(artifact, limits);
            PluginDescriptor descriptor = inspection.descriptor();
            if (expectedId != null && !expectedId.equals(descriptor.id())) {
                throw new IOException("transaction artifact id changed while writing manifest: " + artifact);
            }
            if (expectedVersion != null && !expectedVersion.equals(descriptor.version())) {
                throw new IOException("transaction artifact version changed while writing manifest: " + artifact);
            }
            Path sidecar = provenanceStore.existingManagedSidecarPathStrict(artifact).orElse(null);
            if (requireSidecar && sidecar == null) {
                throw new IOException("transaction artifact provenance is missing: " + artifact);
            }
            if (sidecar != null && requireSidecar) {
                var provenance = provenanceStore.readRequiredForRecovery(artifact);
                VerificationResult verification = verificationService.verifyInstalled(
                        artifact, descriptor, provenance);
                if (!verification.accepted()) {
                    throw new IOException("transaction artifact provenance verification failed: "
                            + artifact + " (" + verification.status() + ")");
                }
            }
            return new ExpectedArtifact(descriptor.id(), descriptor.version(), Files.size(artifact),
                    PluginPackageIntegrity.sha256Hex(artifact),
                    sidecar != null ? PluginPackageIntegrity.sha256Hex(sidecar) : "");
        } catch (PluginPackageException e) {
            throw new IOException("transaction artifact could not be inspected: " + artifact, e);
        }
    }

    private static java.util.Optional<Path> existingPlainFile(Path path) throws IOException {
        BasicFileAttributes attributes = readAttributesIfPresent(path).orElse(null);
        if (attributes == null) {
            return java.util.Optional.empty();
        }
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("transaction artifact is not a plain file: " + path);
        }
        return java.util.Optional.of(path);
    }

    private static void writeExpectedArtifact(Properties properties, String prefix, ExpectedArtifact artifact) {
        properties.setProperty(prefix + ".id", artifact.pluginId());
        properties.setProperty(prefix + ".version", artifact.version());
        properties.setProperty(prefix + ".size", Long.toString(artifact.size()));
        properties.setProperty(prefix + ".sha256", artifact.sha256());
        properties.setProperty(prefix + ".sidecar.sha256", artifact.sidecarSha256());
    }

    private static ReadManifest readManifest(Path manifest) throws IOException {
        BasicFileAttributes attributes = readAttributesIfPresent(manifest).orElse(null);
        if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                || !attributes.isRegularFile()) {
            throw new IOException("plugin transaction manifest is not a plain regular file");
        }
        if (attributes.size() > MAX_RECOVERY_MANIFEST_BYTES) {
            throw new IOException("plugin transaction manifest exceeds the supported size");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long byteCount = 0L;
        try (SeekableByteChannel channel = Files.newByteChannel(manifest, options);
             InputStream input = Channels.newInputStream(channel)) {
            int read;
            while ((read = input.read(buffer, 0, (int) Math.min(
                    buffer.length, MAX_RECOVERY_MANIFEST_BYTES + 1L - byteCount))) != -1) {
                collected.write(buffer, 0, read);
                byteCount += read;
                if (byteCount > MAX_RECOVERY_MANIFEST_BYTES) {
                    throw new ManifestReadException(
                            "plugin transaction manifest grew beyond the supported size while reading",
                            byteCount, null);
                }
            }
        } catch (ManifestReadException e) {
            throw e;
        } catch (IOException e) {
            throw new ManifestReadException(
                    "failed to read plugin transaction manifest", byteCount, e);
        }
        byte[] bytes = collected.toByteArray();
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ManifestReadException(
                    "plugin transaction manifest is not valid UTF-8", bytes.length, e);
        }
        Properties properties = new RejectingProperties();
        try (Reader reader = new StringReader(content)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new ManifestReadException(
                    "plugin transaction manifest properties are invalid", bytes.length, e);
        }
        return new ReadManifest(properties, bytes.length);
    }

    private RecoveryManifest validateRecoveryManifest(Path pluginsRoot, Path transaction, Properties properties)
            throws RecoveryValidationException {
        String formatVersion = requiredProperty(properties, "format.version");
        if (!TRANSACTION_FORMAT_VERSION.equals(formatVersion)) {
            throw invalidManifest("unsupported transaction manifest format.version: " + formatVersion);
        }
        String transactionId = requiredProperty(properties, "transaction.id");
        if (!transactionId.equals(transaction.getFileName().toString())) {
            throw invalidManifest("transaction.id does not match its directory name");
        }
        if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw invalidManifest("transaction.id is not a safe token");
        }

        RecoveryOperation operation;
        try {
            operation = RecoveryOperation.valueOf(requiredProperty(properties, "operation"));
        } catch (IllegalArgumentException e) {
            throw invalidManifest("unknown transaction operation");
        }

        PluginTransactionState state;
        try {
            state = PluginTransactionState.valueOf(requiredProperty(properties, "state"));
        } catch (IllegalArgumentException e) {
            throw invalidManifest("unknown transaction state");
        }
        String packageId = requiredProperty(properties, "package.id");
        if (!PluginDescriptor.ID_PATTERN.matcher(packageId).matches()) {
            throw invalidManifest("invalid manifest package.id");
        }
        String version = properties.getProperty("version");
        if (version == null) {
            throw invalidManifest("missing manifest property: version");
        }

        String targetValue = properties.getProperty("target");
        if (targetValue == null) {
            throw invalidManifest("missing manifest property: target");
        }
        String stagedValue = properties.getProperty("staged");
        if (stagedValue == null) {
            throw invalidManifest("missing manifest property: staged");
        }
        boolean removal = operation == RecoveryOperation.REMOVE;
        if (removal != targetValue.isBlank() || removal != stagedValue.isBlank()) {
            throw invalidManifest(removal
                    ? "removal transaction must not declare a target"
                    : "install transaction must declare target and staged paths");
        }
        if (removal && state != PluginTransactionState.PREPARED
                && state != PluginTransactionState.ROLLING_BACK
                && state != PluginTransactionState.ROLLED_BACK
                && state != PluginTransactionState.COMMITTED) {
            throw invalidManifest("removal transaction has an unsupported state: " + state);
        }
        if (removal) {
            if (!version.isBlank()) {
                throw invalidManifest("removal transaction must not declare a version");
            }
        } else {
            validateVersion(version, "install transaction version");
        }

        Path target = targetValue.isBlank() ? null
                : validateArtifactPath(targetValue, pluginsRoot, pluginsRoot, "target");
        Path stagedRoot = transaction.resolve("new").toAbsolutePath().normalize();
        Path staged = stagedValue.isBlank() ? null
                : validateArtifactPath(stagedValue, stagedRoot, pluginsRoot, "staged artifact");
        ExpectedArtifact newArtifact = removal ? null : parseExpectedArtifact(properties, "artifact");
        if (!removal) {
            if (!packageId.equals(newArtifact.pluginId()) || !version.equals(newArtifact.version())) {
                throw invalidManifest("package identity does not match the staged artifact identity");
            }
            requireCanonicalArtifactName(target, packageId, version, "target");
            if (!target.getFileName().equals(staged.getFileName())) {
                throw invalidManifest("target and staged artifact names differ");
            }
            requireCanonicalArtifactName(staged, packageId, version, "staged artifact");
            if (!newArtifact.hasSidecar()) {
                throw invalidManifest("install transaction must bind the staged provenance sidecar");
            }
        } else if (!blankProperty(properties, "artifact.id")
                || !blankProperty(properties, "artifact.version")
                || !blankProperty(properties, "artifact.size")
                || !blankProperty(properties, "artifact.sha256")
                || !blankProperty(properties, "artifact.sidecar.sha256")) {
            throw invalidManifest("removal transaction must not declare a new artifact digest");
        }

        int replacesCount = parseBoundedCount(properties, "replaces.count", MAX_RECOVERY_BACKUPS);
        Set<String> replaces = new LinkedHashSet<>();
        for (int i = 0; i < replacesCount; i++) {
            String replacedId = requiredProperty(properties, "replaces." + i);
            if (!PluginDescriptor.ID_PATTERN.matcher(replacedId).matches()
                    || replacedId.equals(packageId) || !replaces.add(replacedId)) {
                throw invalidManifest("invalid or duplicate replaced plugin id");
            }
        }
        if (removal && !replaces.isEmpty()) {
            throw invalidManifest("removal transaction must not declare replacement ids");
        }

        int backupCount = parseBackupCount(properties);
        if (removal && backupCount == 0) {
            throw invalidManifest("removal transaction must declare at least one backup");
        }

        Path removedRoot = transaction.resolve(BACKUP_SUBDIR).toAbsolutePath().normalize();
        if (backupCount > 0) {
            requirePathWithin(removedRoot, transaction, "backup root");
            assertExistingPathComponentsSafe(pluginsRoot, removedRoot, "backup root");
            try {
                BasicFileAttributes attributes = readAttributesIfPresent(removedRoot).orElse(null);
                if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory())) {
                    throw unsafePath("backup root is not a plain directory: " + removedRoot);
                }
            } catch (IOException e) {
                throw unsafePath("backup root could not be inspected: " + describeRecoveryFailure(e));
            }
        }

        List<RecoveryBackup> backups = new ArrayList<>(backupCount);
        Set<Path> claimedPaths = new LinkedHashSet<>();
        if (target != null) {
            claimedPaths.add(target);
            claimedPaths.add(staged);
        }
        for (int i = 0; i < backupCount; i++) {
            ExpectedArtifact expected = parseExpectedArtifact(properties, "backup." + i);
            if (removal && !packageId.equals(expected.pluginId())) {
                throw invalidManifest("removal backup identity does not match package.id");
            }
            if (!removal && !packageId.equals(expected.pluginId()) && !replaces.contains(expected.pluginId())) {
                throw invalidManifest("install backup identity is not owned or explicitly replaced");
            }
            Path origin = validateArtifactPath(requiredProperty(properties, "backup." + i + ".origin"),
                    pluginsRoot, pluginsRoot, "backup origin");
            Path backup = validateArtifactPath(requiredProperty(properties, "backup." + i + ".path"),
                    removedRoot, pluginsRoot, "backup path");
            // 既有安装包可能来自旧版本或人工复制，扫描事实以包内 descriptor 为准；恢复清单必须原样保留
            // 其安全的根级文件名。只有本事务生成的新 target/staged 强制规范命名。
            String expectedBackupName = i + "-" + origin.getFileName();
            if (!backup.getFileName().toString().equals(expectedBackupName)) {
                throw invalidManifest("backup path is not bound to its index and origin name");
            }
            if (!claimedPaths.add(origin) || !claimedPaths.add(backup)) {
                throw invalidManifest("transaction declares a duplicate artifact path");
            }
            backups.add(new RecoveryBackup(expected, origin, backup));
        }
        validateManifestPropertyKeys(properties, replacesCount, backupCount);
        return new RecoveryManifest(operation, state, packageId, version, target, staged,
                newArtifact, List.copyOf(replaces), List.copyOf(backups));
    }

    private static void validateManifestPropertyKeys(Properties properties, int replacesCount, int backupCount)
            throws RecoveryValidationException {
        Set<String> allowed = new LinkedHashSet<>(List.of(
                "format.version", "transaction.id", "operation", "state", "package.id", "version",
                "target", "staged", "artifact.id", "artifact.version", "artifact.size",
                "artifact.sha256", "artifact.sidecar.sha256", "replaces.count", "backup.count"));
        for (int i = 0; i < replacesCount; i++) {
            allowed.add("replaces." + i);
        }
        for (int i = 0; i < backupCount; i++) {
            String prefix = "backup." + i;
            allowed.add(prefix + ".id");
            allowed.add(prefix + ".version");
            allowed.add(prefix + ".size");
            allowed.add(prefix + ".sha256");
            allowed.add(prefix + ".sidecar.sha256");
            allowed.add(prefix + ".origin");
            allowed.add(prefix + ".path");
        }
        for (String key : properties.stringPropertyNames()) {
            if (!allowed.contains(key)) {
                throw invalidManifest("unknown transaction manifest property: " + key);
            }
        }
    }

    private Path validateArtifactPath(String value, Path expectedParent, Path pluginsRoot, String role)
            throws RecoveryValidationException {
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException e) {
            throw unsafePath(role + " is not a valid path");
        }
        if (!path.isAbsolute()) {
            throw unsafePath(role + " must be absolute: " + value);
        }
        Path normalized = path.normalize();
        if (!path.equals(normalized)) {
            throw unsafePath(role + " must already be normalized: " + value);
        }
        if (normalized.getParent() == null || !normalized.getParent().equals(expectedParent)) {
            throw unsafePath(role + " escapes its expected root: " + value);
        }
        String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || !(name.endsWith(".jar") || name.endsWith(".zip"))) {
            throw unsafePath(role + " is not a plugin artifact path: " + value);
        }
        requirePathWithin(normalized, pluginsRoot, role);
        assertExistingPathComponentsSafe(pluginsRoot, normalized, role);
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(normalized).orElse(null);
            if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isRegularFile())) {
                throw unsafePath(role + " is not a plain regular file: " + value);
            }
        } catch (IOException e) {
            throw unsafePath(role + " could not be inspected: " + describeRecoveryFailure(e));
        }
        validateProvenancePath(normalized, pluginsRoot, role);
        return normalized;
    }

    private void validateProvenancePath(Path artifact, Path pluginsRoot, String role)
            throws RecoveryValidationException {
        Path provenanceRoot = provenanceStore.provenanceDir().toAbsolutePath().normalize();
        for (Path configuredSidecar : provenanceStore.managedSidecarPaths(artifact)) {
            Path sidecar = configuredSidecar.toAbsolutePath().normalize();
            Path sidecarParent = sidecar.getParent();
            if (sidecarParent == null
                    || !sidecarParent.equals(artifact.getParent()) && !sidecarParent.equals(provenanceRoot)) {
                throw unsafePath(role + " provenance path escapes its expected root: " + sidecar);
            }
            requirePathWithin(sidecar, pluginsRoot, role + " provenance");
            assertExistingPathComponentsSafe(pluginsRoot, sidecar, role + " provenance");
            try {
                BasicFileAttributes parentAttributes = readAttributesIfPresent(sidecarParent).orElse(null);
                if (parentAttributes != null && (parentAttributes.isSymbolicLink() || parentAttributes.isOther()
                        || !parentAttributes.isDirectory())) {
                    throw unsafePath(role + " provenance root is not a plain directory: " + sidecarParent);
                }
                BasicFileAttributes attributes = readAttributesIfPresent(sidecar).orElse(null);
                if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isRegularFile())) {
                    throw unsafePath(role + " provenance is not a plain regular file: " + sidecar);
                }
            } catch (IOException e) {
                throw unsafePath(role + " provenance could not be inspected: " + describeRecoveryFailure(e));
            }
        }
    }

    private static void requirePathWithin(Path path, Path expectedRoot, String role)
            throws RecoveryValidationException {
        Path normalizedRoot = expectedRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw unsafePath(role + " escapes its expected root: " + path);
        }
    }

    private static void assertExistingPathComponentsSafe(Path pluginsRoot, Path path, String role)
            throws RecoveryValidationException {
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
            throws RecoveryValidationException {
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

    private ExpectedArtifact parseExpectedArtifact(Properties properties, String prefix)
            throws RecoveryValidationException {
        String pluginId = requiredProperty(properties, prefix + ".id");
        if (!PluginDescriptor.ID_PATTERN.matcher(pluginId).matches()) {
            throw invalidManifest("invalid " + prefix + " plugin id");
        }
        String version = requiredProperty(properties, prefix + ".version");
        validateVersion(version, prefix + " version");

        long size;
        try {
            size = Long.parseLong(requiredProperty(properties, prefix + ".size"));
        } catch (NumberFormatException e) {
            throw invalidManifest(prefix + ".size is not an integer");
        }
        if (size <= 0L) {
            throw invalidManifest(prefix + ".size must be positive");
        }
        if (size > limits.maxArchiveBytes()) {
            throw invalidManifest(prefix + ".size exceeds the configured archive limit");
        }
        String sha256 = requiredSha256(properties, prefix + ".sha256", false);
        String sidecarKey = prefix + ".sidecar.sha256";
        String sidecarValue = properties.getProperty(sidecarKey);
        if (sidecarValue == null) {
            throw invalidManifest("missing manifest property: " + sidecarKey);
        }
        String sidecarSha256 = sidecarValue.isBlank()
                ? "" : normalizeSha256(sidecarValue, sidecarKey);
        return new ExpectedArtifact(pluginId, version, size, sha256, sidecarSha256);
    }

    private static String requiredSha256(Properties properties, String key, boolean allowBlank)
            throws RecoveryValidationException {
        String value = properties.getProperty(key);
        if (value == null || !allowBlank && value.isBlank()) {
            throw invalidManifest("missing manifest property: " + key);
        }
        if (value.isBlank()) {
            return "";
        }
        return normalizeSha256(value, key);
    }

    private static String normalizeSha256(String value, String key) throws RecoveryValidationException {
        if (!value.matches("[0-9A-Fa-f]{64}")) {
            throw invalidManifest(key + " is not a SHA-256 digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean blankProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value != null && value.isBlank();
    }

    private static void validateVersion(String version, String role) throws RecoveryValidationException {
        if (!version.equals(version.trim())
                || !version.matches("\\d+\\.\\d+\\.\\d+"
                + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")) {
            throw invalidManifest(role + " is not a valid semantic version");
        }
    }

    private static void requireCanonicalArtifactName(Path artifact, String pluginId, String version, String role)
            throws RecoveryValidationException {
        String actual = artifact.getFileName().toString();
        if (!actual.equals(pluginId + "-" + version + ".jar")
                && !actual.equals(pluginId + "-" + version + ".zip")) {
            throw invalidManifest(role + " is not canonically named for its declared identity");
        }
    }

    /**
     * 清单声明之外的 transaction entry 一律拒绝；Files.walk 不跟随链接，且每个 entry 再以 NOFOLLOW_LINKS
     * 检查，避免 junction / reparse point 越过事务边界。
     */
    private int validateTransactionTree(Path transaction, RecoveryManifest manifest)
            throws IOException, RecoveryValidationException {
        Path normalizedTransaction = transaction.toAbsolutePath().normalize();
        Set<Path> allowedDirectories = new LinkedHashSet<>();
        Set<Path> allowedFiles = new LinkedHashSet<>();
        allowedDirectories.add(normalizedTransaction);
        allowedFiles.add(normalizedTransaction.resolve(TRANSACTION_MANIFEST));
        allowedFiles.add(normalizedTransaction.resolve(TRANSACTION_MANIFEST + ".tmp"));
        if (manifest.operation() == RecoveryOperation.INSTALL) {
            allowedDirectories.add(normalizedTransaction.resolve("new"));
            addArtifactAndSidecars(allowedFiles, manifest.staged());
        }
        if (!manifest.backups().isEmpty()) {
            allowedDirectories.add(normalizedTransaction.resolve(BACKUP_SUBDIR));
            for (RecoveryBackup backup : manifest.backups()) {
                addArtifactAndSidecars(allowedFiles, backup.backup());
            }
        }

        int entries = 0;
        try (Stream<Path> walk = Files.walk(normalizedTransaction)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (++entries > MAX_RECOVERY_TRANSACTION_ENTRIES) {
                    throw invalidManifest("transaction tree exceeds the supported entry count");
                }
                Path normalized = entry.toAbsolutePath().normalize();
                BasicFileAttributes attributes = Files.readAttributes(
                        normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw unsafePath("transaction tree contains a symbolic link or reparse/special entry: "
                            + normalized);
                }
                if (attributes.isDirectory()) {
                    if (!allowedDirectories.contains(normalized)) {
                        throw unsafePath("transaction tree contains an undeclared directory: " + normalized);
                    }
                } else if (attributes.isRegularFile()) {
                    if (!allowedFiles.contains(normalized)) {
                        throw unsafePath("transaction tree contains an undeclared file: " + normalized);
                    }
                } else {
                    throw unsafePath("transaction tree contains an unsupported entry: " + normalized);
                }
            }
        }
        return entries;
    }

    private void addArtifactAndSidecars(Set<Path> allowedFiles, Path artifact) {
        allowedFiles.add(artifact.toAbsolutePath().normalize());
        for (Path sidecar : provenanceStore.managedSidecarPaths(artifact)) {
            allowedFiles.add(sidecar.toAbsolutePath().normalize());
        }
    }

    /** 复核清单状态与磁盘分布；任何重复、副本缺失或摘要漂移都在第一次写入前失败。 */
    private RecoveryState validateRecoveryState(RecoveryManifest manifest) throws RecoveryValidationException {
        return validateRecoveryState(manifest, new RecoveryBudget());
    }

    private RecoveryState validateRecoveryState(RecoveryManifest manifest, RecoveryBudget budget)
            throws RecoveryValidationException {
        List<LogicalArtifactState> backupStates = new ArrayList<>(manifest.backups().size());
        for (RecoveryBackup backup : manifest.backups()) {
            LogicalArtifactState observed = inspectLogicalArtifact(
                    backup.expected(), backup.origin(), backup.backup(), budget);
            boolean finalized = manifest.state() == PluginTransactionState.COMMITTED
                    || manifest.operation() == RecoveryOperation.INSTALL
                    && manifest.state() == PluginTransactionState.ACTIVATED;
            if (manifest.state() == PluginTransactionState.ROLLED_BACK) {
                requireBackupRestored(backup, observed);
            } else if (finalized) {
                if (!observed.artifactAt(backup.backup())
                        || backup.expected().hasSidecar() && !observed.sidecarAt(backup.backup())) {
                    throw unsafePath("completed transaction does not retain its declared backup: "
                            + backup.backup());
                }
            } else {
                if (observed.artifactOwner() == null) {
                    throw unsafePath("declared backup artifact is missing: " + backup.origin());
                }
                if (backup.expected().hasSidecar() && observed.sidecarOwner() == null) {
                    throw unsafePath("declared backup provenance is missing: " + backup.origin());
                }
            }
            backupStates.add(observed);
        }

        if (manifest.operation() == RecoveryOperation.INSTALL) {
            LogicalArtifactState newState = inspectLogicalArtifact(
                    manifest.newArtifact(), manifest.staged(), manifest.target(), budget);
            if (newState.inspection() != null
                    && !List.copyOf(newState.inspection().descriptor().replaces()).equals(manifest.replaces())) {
                throw unsafePath("transaction replaces declaration does not match the new artifact descriptor");
            }
            switch (manifest.state()) {
                case PREPARED -> {
                    requireNewArtifactInspection(newState);
                    requireOwners(newState, manifest.staged(), manifest.staged(), "prepared artifact");
                }
                case OLD_ISOLATED -> {
                    requireNewArtifactInspection(newState);
                    if (newState.artifactOwner() == null || newState.sidecarOwner() == null) {
                        throw unsafePath("old-isolated transaction has an incomplete new artifact");
                    }
                }
                case NEW_PLACED, ACTIVATED, COMMITTED -> {
                    requireNewArtifactInspection(newState);
                    requireOwners(newState, manifest.target(), manifest.target(), "placed artifact");
                }
                case ROLLING_BACK -> {
                    // artifact 与 sidecar 可分别位于 staged / target，或已按单调回滚流程被删除。
                }
                case ROLLED_BACK -> {
                    if (newState.artifactOwner() != null || newState.sidecarOwner() != null) {
                        throw unsafePath("rolled-back transaction still owns the new artifact");
                    }
                }
            }
        }
        return new RecoveryState(List.copyOf(backupStates));
    }

    private static void requireNewArtifactInspection(LogicalArtifactState state)
            throws RecoveryValidationException {
        if (state.inspection() == null) {
            throw unsafePath("transaction new artifact is missing or incomplete");
        }
    }

    private static void requireBackupRestored(RecoveryBackup backup, LogicalArtifactState state)
            throws RecoveryValidationException {
        if (!state.artifactAt(backup.origin())
                || backup.expected().hasSidecar() && !state.sidecarAt(backup.origin())
                || !backup.expected().hasSidecar() && state.sidecarOwner() != null) {
            throw unsafePath("rolled-back artifact is not fully restored at its origin: " + backup.origin());
        }
    }

    private static void requireOwners(LogicalArtifactState observed, Path artifactOwner, Path sidecarOwner,
                                      String role) throws RecoveryValidationException {
        if (!observed.artifactAt(artifactOwner) || !observed.sidecarAt(sidecarOwner)) {
            throw unsafePath(role + " is not at its state-bound path");
        }
    }

    private LogicalArtifactState inspectLogicalArtifact(ExpectedArtifact expected, Path first, Path second)
            throws RecoveryValidationException {
        return inspectLogicalArtifact(expected, first, second, new RecoveryBudget());
    }

    private LogicalArtifactState inspectLogicalArtifact(ExpectedArtifact expected, Path first, Path second,
                                                        RecoveryBudget budget)
            throws RecoveryValidationException {
        Path artifactOwner = null;
        Path artifactAlias = null;
        Path sidecarOwner = null;
        Path sidecarAlias = null;
        Path sidecarPath = null;
        PluginPackageInspection artifactInspection = null;
        for (Path candidate : List.of(first, second)) {
            try {
                BasicFileAttributes attributes = readAttributesIfPresent(candidate).orElse(null);
                if (attributes != null) {
                    PluginPackageInspection candidateInspection = inspectBoundArtifact(
                            candidate, expected, "transaction artifact", budget);
                    if (artifactOwner == null) {
                        artifactOwner = candidate;
                        artifactInspection = candidateInspection;
                    } else if (!Files.isSameFile(artifactOwner, candidate)) {
                        throw unsafePath("artifact has multiple physical copies: " + first + " and " + second);
                    } else {
                        artifactAlias = candidate;
                    }
                }
                Path sidecar = provenanceStore.existingManagedSidecarPathStrict(candidate).orElse(null);
                if (sidecar != null) {
                    if (!expected.hasSidecar()) {
                        throw unsafePath("unexpected provenance sidecar: " + sidecar);
                    }
                    String digest = PluginPackageIntegrity.sha256Hex(sidecar);
                    if (!expected.sidecarSha256().equals(digest)) {
                        throw unsafePath("provenance digest does not match its manifest binding: " + sidecar);
                    }
                    if (sidecarOwner == null) {
                        sidecarOwner = candidate;
                        sidecarPath = sidecar;
                    } else if (!Files.isSameFile(sidecarPath, sidecar)) {
                        throw unsafePath("provenance has multiple physical copies: " + first + " and " + second);
                    } else {
                        sidecarAlias = candidate;
                    }
                }
            } catch (IOException e) {
                throw unsafePath("transaction artifact state could not be inspected: "
                        + describeRecoveryFailure(e));
            }
        }
        return new LogicalArtifactState(
                artifactOwner, artifactAlias, sidecarOwner, sidecarAlias, artifactInspection);
    }

    private PluginPackageInspection inspectBoundArtifact(Path artifact, ExpectedArtifact expected, String role)
            throws RecoveryValidationException {
        return inspectBoundArtifact(artifact, expected, role, new RecoveryBudget());
    }

    private PluginPackageInspection inspectBoundArtifact(
            Path artifact, ExpectedArtifact expected, String role, RecoveryBudget budget)
            throws RecoveryValidationException {
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(artifact).orElse(null);
            if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isRegularFile()) {
                throw unsafePath(role + " is missing or is not a plain regular file: " + artifact);
            }
            if (attributes.size() != expected.size()) {
                throw unsafePath(role + " size does not match its manifest binding: " + artifact);
            }
            if (attributes.size() > limits.maxArchiveBytes()) {
                throw unsafePath(role + " exceeds the configured archive limit before hashing: " + artifact);
            }
            String digest = PluginPackageIntegrity.sha256Hex(artifact);
            if (!expected.sha256().equals(digest)) {
                throw unsafePath(role + " digest does not match its manifest binding: " + artifact);
            }
            PluginPackageInspection inspection = budget.inspectArchive(artifact, digest, limits);
            PluginDescriptor descriptor = inspection.descriptor();
            if (!expected.pluginId().equals(descriptor.id())
                    || !expected.version().equals(descriptor.version())
                    || !descriptor.externalValidationErrors().isEmpty()) {
                throw unsafePath(role + " package identity is not valid or does not match its manifest binding: "
                        + artifact);
            }
            return inspection;
        } catch (IOException | PluginPackageException e) {
            throw unsafePath(role + " could not be verified: " + describeRecoveryFailure(e));
        }
    }

    private static int parseBackupCount(Properties properties) throws RecoveryValidationException {
        return parseBoundedCount(properties, "backup.count", MAX_RECOVERY_BACKUPS);
    }

    private static int parseBoundedCount(Properties properties, String key, int maximum)
            throws RecoveryValidationException {
        String value = requiredProperty(properties, key);
        int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalidManifest(key + " is not an integer");
        }
        if (count < 0 || count > maximum) {
            throw invalidManifest(key + " is outside the supported range");
        }
        return count;
    }

    private static String requiredProperty(Properties properties, String key) throws RecoveryValidationException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalidManifest("missing manifest property: " + key);
        }
        return value;
    }

    private static Failure recoveryFailure(String transactionId, Path transaction, FailureKind kind, String detail) {
        return new Failure(transactionId, transaction, kind, detail);
    }

    private static RecoveryValidationException invalidManifest(String message) {
        return new RecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }

    private static RecoveryValidationException unsafePath(String message) {
        return new RecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }

    private static String describeRecoveryFailure(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    private void cleanupHiddenWorkspaceRoot(Path pluginsRoot, String directoryName)
            throws IOException, RecoveryValidationException {
        Path workspaceRoot = pluginsRoot.resolve(directoryName);
        BasicFileAttributes rootAttributes = readAttributesIfPresent(workspaceRoot).orElse(null);
        if (rootAttributes == null) {
            return;
        }
        if (rootAttributes.isSymbolicLink() || rootAttributes.isOther() || !rootAttributes.isDirectory()) {
            throw unsafePath("hidden transaction workspace must be a plain directory: " + workspaceRoot);
        }
        List<Path> workspaces = new ArrayList<>();
        try (Stream<Path> entries = Files.list(workspaceRoot)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (workspaces.size() >= MAX_HIDDEN_WORKSPACES) {
                    throw invalidManifest("hidden transaction workspace exceeds the supported count");
                }
                Path workspace = iterator.next().toAbsolutePath().normalize();
                BasicFileAttributes attributes = readAttributesIfPresent(workspace).orElse(null);
                if (attributes == null || attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory() || !Objects.equals(workspace.getParent(), workspaceRoot)) {
                    throw unsafePath("hidden transaction workspace contains an unsafe entry: " + workspace);
                }
                workspaces.add(workspace);
            }
        }
        int remainingEntries = MAX_HIDDEN_WORKSPACE_ENTRIES;
        for (Path workspace : workspaces) {
            List<Path> deletionOrder = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(workspace)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    if (remainingEntries-- <= 0) {
                        throw invalidManifest("hidden transaction workspaces exceed the cumulative entry budget");
                    }
                    Path entry = iterator.next().toAbsolutePath().normalize();
                    BasicFileAttributes attributes = Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw unsafePath("hidden transaction workspace contains a link or special entry: " + entry);
                    }
                    deletionOrder.add(entry);
                }
            }
            deletionOrder.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path entry : deletionOrder) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(workspaceRoot);
    }

    /**
     * 未发布准备区和已退役清理区都不是权威恢复面；无法安全删除时原样保留并告警，不能因此绕过或封闭后续
     * {@code .staging} 校验。artifact scanner 会继续忽略这些隐藏路径。
     */
    private void cleanupHiddenWorkspaceRootBestEffort(Path pluginsRoot, String directoryName) {
        try {
            cleanupHiddenWorkspaceRoot(pluginsRoot, directoryName);
        } catch (IOException | RecoveryValidationException | RuntimeException e) {
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
                    staging.resolve(BACKUP_SUBDIR));
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

    private enum RecoveryOperation {
        INSTALL,
        REMOVE
    }

    private enum RemovalFailureOutcome {
        ROLLED_BACK,
        REMOVED,
        UNSAFE
    }

    private record PublishedTransactionFailure(boolean publishedOrUncertain, Throwable failure) {
    }

    private record ExpectedArtifact(String pluginId, String version, long size, String sha256,
                                    String sidecarSha256) {

        private boolean hasSidecar() {
            return sidecarSha256 != null && !sidecarSha256.isBlank();
        }
    }

    private record RecoveryBackup(ExpectedArtifact expected, Path origin, Path backup) {
    }

    private record RecoveryManifest(RecoveryOperation operation, PluginTransactionState state,
                                    String packageId, String version, Path target, Path staged,
                                    ExpectedArtifact newArtifact, List<String> replaces,
                                    List<RecoveryBackup> backups) {

        private Set<Path> claimedArtifactPaths() {
            Set<Path> claims = new LinkedHashSet<>();
            if (target != null) {
                claims.add(target);
            }
            if (staged != null) {
                claims.add(staged);
            }
            for (RecoveryBackup backup : backups) {
                claims.add(backup.origin());
                claims.add(backup.backup());
            }
            return Set.copyOf(claims);
        }
    }

    private record RecoveryPlan(String transactionId, Path transaction, RecoveryManifest manifest) {
    }

    private static final class RejectingProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("duplicate transaction manifest property: " + key);
            }
            return super.put(key, value);
        }
    }

    private static final class RecoveryBudget {
        private long manifestBytes;
        private int backups;
        private int entries;
        private long artifactBytes;
        private long sidecarBytes;
        private int archiveEntries;
        private long uncompressedBytes;
        private boolean exhausted;
        private final java.util.Map<String, PluginPackageInspection> archiveInspections =
                new java.util.LinkedHashMap<>();

        private void requireAvailable() throws RecoveryValidationException {
            if (exhausted) {
                throw invalidManifest("recovery cumulative resource budget is already exhausted");
            }
        }

        private boolean exhausted() {
            return exhausted;
        }

        private void consumeManifestBytes(long bytes) throws RecoveryValidationException {
            manifestBytes = boundedAddOrExhaust(manifestBytes, bytes, MAX_RECOVERY_TOTAL_MANIFEST_BYTES,
                    "recovery manifests exceed the cumulative byte budget");
        }

        private void consumeManifest(RecoveryManifest manifest) throws RecoveryValidationException {
            requireAvailable();
            if (manifest.backups().size() > MAX_RECOVERY_TOTAL_BACKUPS - backups) {
                throw exhaust("recovery backups exceed the cumulative count budget");
            }
            backups += manifest.backups().size();
            long declaredBytes = manifest.newArtifact() != null ? manifest.newArtifact().size() : 0L;
            for (RecoveryBackup backup : manifest.backups()) {
                declaredBytes = boundedAddOrExhaust(declaredBytes, backup.expected().size(),
                        MAX_RECOVERY_TOTAL_ARTIFACT_BYTES,
                        "recovery artifacts exceed the cumulative byte budget");
            }
            artifactBytes = boundedAddOrExhaust(artifactBytes, declaredBytes,
                    MAX_RECOVERY_TOTAL_ARTIFACT_BYTES,
                    "recovery artifacts exceed the cumulative byte budget");
        }

        private void consumeEntries(int count) throws RecoveryValidationException {
            requireAvailable();
            if (count > MAX_RECOVERY_TOTAL_ENTRIES - entries) {
                throw exhaust("recovery transaction trees exceed the cumulative entry budget");
            }
            entries += count;
        }

        private void consumeSidecarBytes(long bytes) throws RecoveryValidationException {
            sidecarBytes = boundedAddOrExhaust(sidecarBytes, bytes, MAX_RECOVERY_TOTAL_SIDECAR_BYTES,
                    "recovery provenance exceeds the cumulative byte budget");
        }

        private PluginPackageInspection inspectArchive(Path artifact, String sha256, PluginPackageLimits limits)
                throws RecoveryValidationException {
            requireAvailable();
            PluginPackageInspection cached = archiveInspections.get(sha256);
            if (cached != null) {
                return cached;
            }
            int remainingEntries = MAX_RECOVERY_TOTAL_ARCHIVE_ENTRIES - archiveEntries;
            long remainingUncompressed = MAX_RECOVERY_TOTAL_UNCOMPRESSED_BYTES - uncompressedBytes;
            if (remainingEntries <= 0 || remainingUncompressed <= 0L) {
                throw exhaust("plugin archives exceed the cumulative recovery verification budget");
            }
            PluginPackageLimits effectiveLimits = new PluginPackageLimits(
                    limits.maxArchiveBytes(),
                    Math.min(limits.maxEntries(), remainingEntries),
                    Math.min(limits.maxTotalUncompressedBytes(), remainingUncompressed),
                    Math.min(limits.maxEntryUncompressedBytes(), remainingUncompressed),
                    limits.maxDescriptorBytes(),
                    limits.maxCompressionRatio());
            boolean constrainedByRemainingBudget = effectiveLimits.maxEntries() < limits.maxEntries()
                    || effectiveLimits.maxTotalUncompressedBytes() < limits.maxTotalUncompressedBytes()
                    || effectiveLimits.maxEntryUncompressedBytes() < limits.maxEntryUncompressedBytes();
            PluginPackageVerifier.VerificationUsage usage;
            try {
                usage = PluginPackageVerifier.verifyAndMeasure(artifact, effectiveLimits);
            } catch (PluginPackageException failure) {
                if (failure.hasVerificationUsage()) {
                    consumeArchiveUsage(
                            failure.consumedEntries(), failure.consumedUncompressedBytes());
                } else if (constrainedByRemainingBudget) {
                    exhausted = true;
                }
                throw failure;
            }
            consumeArchiveUsage(usage.entryCount(), usage.totalUncompressedBytes());
            PluginPackageInspection inspection = PluginPackageReader.inspect(artifact, limits);
            archiveInspections.put(sha256, inspection);
            return inspection;
        }

        private void consumeArchiveUsage(int consumedEntries, long consumedBytes)
                throws RecoveryValidationException {
            if (consumedEntries < 0 || consumedBytes < 0L
                    || consumedEntries > MAX_RECOVERY_TOTAL_ARCHIVE_ENTRIES - archiveEntries
                    || consumedBytes > MAX_RECOVERY_TOTAL_UNCOMPRESSED_BYTES - uncompressedBytes) {
                throw exhaust("plugin archives exceed the cumulative recovery verification budget");
            }
            archiveEntries += consumedEntries;
            uncompressedBytes += consumedBytes;
        }

        private long boundedAddOrExhaust(long current, long increment, long maximum, String message)
                throws RecoveryValidationException {
            requireAvailable();
            if (increment < 0L || current > maximum - increment) {
                throw exhaust(message);
            }
            return current + increment;
        }

        private RecoveryValidationException exhaust(String message) {
            exhausted = true;
            return invalidManifest(message);
        }
    }

    private record LogicalArtifactState(
            Path artifactOwner,
            Path artifactAlias,
            Path sidecarOwner,
            Path sidecarAlias,
            PluginPackageInspection inspection) {

        private boolean artifactAt(Path path) {
            return Objects.equals(path, artifactOwner) || Objects.equals(path, artifactAlias);
        }

        private boolean sidecarAt(Path path) {
            return Objects.equals(path, sidecarOwner) || Objects.equals(path, sidecarAlias);
        }
    }

    private record RecoveryState(List<LogicalArtifactState> backups) {
    }

    private record VisibleArtifact(Path path, PluginDescriptor descriptor) {

        private VisibleArtifact {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private record VisibleArtifactInventory(List<VisibleArtifact> artifacts) {

        private VisibleArtifactInventory {
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        }

        private static VisibleArtifactInventory empty() {
            return new VisibleArtifactInventory(List.of());
        }
    }

    private record ReadManifest(Properties properties, long byteCount) {

        private ReadManifest {
            properties = Objects.requireNonNull(properties, "properties");
            if (byteCount < 0L || byteCount > MAX_RECOVERY_MANIFEST_BYTES) {
                throw new IllegalArgumentException("manifest byte count is outside the supported range");
            }
        }
    }

    private static final class ManifestReadException extends IOException {

        private final long byteCount;

        private ManifestReadException(String message, long byteCount, Throwable cause) {
            super(message, cause);
            this.byteCount = byteCount;
        }

        private long byteCount() {
            return byteCount;
        }
    }

    private static final class RecoveryValidationException extends Exception {

        private final FailureKind kind;

        private RecoveryValidationException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }
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
        } catch (IOException | RecoveryValidationException | RuntimeException e) {
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
