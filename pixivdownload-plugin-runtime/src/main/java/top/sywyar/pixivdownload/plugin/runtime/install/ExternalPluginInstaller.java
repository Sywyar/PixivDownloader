package top.sywyar.pixivdownload.plugin.runtime.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
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
 *       {@code .staging/{opId}/}（与目标同卷，保证原子移动）；再把<b>同 id 被取代旧包</b>（规范目标自身除外）原子移入隔离备份
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
 * 构造不创建目录、无副作用；安装目录在首次 {@link #install} 提交时按需创建（目录创建本就归安装流程）。
 * 本安装器只负责落盘，不校验插件主类是否实现入口契约（需加载类，属运行期发现桥接）。
 *
 * <h2>并发串行化</h2>
 * {@link #install} 从 {@link #listInstalled()} 读现存包到提交落盘是一段「检查后动作」临界区；本实例用一把
 * {@link ReentrantLock} 把整段 {@code install} 串行化，使同一进程内对<b>同一安装目录</b>（即同一实例，@Bean 单例）的
 * 并发安装不会交错——并发安装同一 pluginId 不会产生互相覆盖、不稳定落盘或 {@code .staging} 残留。锁只护本实例的
 * 临界区、<b>不</b>持任何 classloader / 路径全局引用（不引入按目录的全局锁表，避免 {@link Path} 引用泄漏）。
 *
 * <h2>来源</h2>
 * {@link #install(Path, boolean)} 默认按 {@link PluginPackageSource#LOCAL_UPLOAD} 处理（本地上传，无完整性期望，
 * <b>当前唯一接入的来源</b>）。{@link #install(Path, boolean, PluginPackageOrigin)} 接受携带来源与完整性期望的描述，
 * 供后续受信目录获取流程在落盘前做完整性校验；本类自身<b>不</b>发起任何网络访问。
 */
public class ExternalPluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginInstaller.class);

    /** 安装目录内的隐藏暂存子目录名（以 {@code .} 开头，PF4J 扫描会跳过它）。 */
    static final String STAGING_DIR = ".staging";

    /** 暂存目录内存放「被取代旧包隔离备份」的子目录名。 */
    private static final String BACKUP_SUBDIR = "removed";

    private final Path pluginsDir;
    private final PluginPackageLimits limits;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private PluginArtifactVerificationService verificationService;
    private final PluginProvenanceStore provenanceStore;
    /** 把整段 {@link #install} 串行化（同一实例 / 同一安装目录的并发安装互斥）。 */
    private final ReentrantLock installLock = new ReentrantLock();

    private static final String TRANSACTION_MANIFEST = "transaction.properties";

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
        if (pluginsDir == null) {
            throw new IllegalArgumentException("pluginsDir must not be null");
        }
        this.pluginsDir = pluginsDir;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
        this.provenanceStore = new PluginProvenanceStore(this.pluginsDir);
    }

    /** 配置的安装目录（未规范化）。 */
    public Path pluginsDirectory() {
        return pluginsDir;
    }

    /** 由宿主在配置解析后刷新统一验签门面；插件代码没有调用入口。 */
    public synchronized void updateVerifier(PluginSupplyChainVerifier verifier) {
        updateVerifierResolver(fixedVerifier(verifier));
    }

    /** 由宿主在配置解析后刷新按来源解析的验签门面；插件代码没有调用入口。 */
    public synchronized void updateVerifierResolver(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
    }

    /** 安装一个本地上传的插件包，默认<b>拒绝降级</b>（已存在更高版本则 {@code DOWNGRADE_REJECTED}）。 */
    public PluginInstallResult install(Path packagePath) {
        return install(packagePath, false);
    }

    /**
     * 安装一个本地上传的插件包（来源 {@link PluginPackageSource#LOCAL_UPLOAD}，无完整性期望）。
     *
     * @param packagePath    {@code .zip} / {@code .jar} 安装包路径
     * @param allowDowngrade 是否允许覆盖更高版本（force；仅内部参数，无 UI）
     */
    public PluginInstallResult install(Path packagePath, boolean allowDowngrade) {
        return install(packagePath, allowDowngrade, PluginPackageOrigin.localUpload());
    }

    /**
     * 安装一个插件包，按来源 {@code origin} 决定是否做完整性校验。整段串行化（{@link #installLock}）。
     *
     * @param packagePath    {@code .zip} / {@code .jar} 安装包路径
     * @param allowDowngrade 是否允许覆盖更高版本（force；仅内部参数，无 UI）
     * @param origin         包来源 + 完整性期望（本地上传无期望；受信目录来源带期望）
     */
    public PluginInstallResult install(Path packagePath, boolean allowDowngrade, PluginPackageOrigin origin) {
        installLock.lock();
        try {
            return installExclusive(packagePath, allowDowngrade,
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
        try {
            String transactionId = UUID.randomUUID().toString();
            Path transaction = pluginsDir.resolve(STAGING_DIR).resolve(transactionId);
            Path validationDir = transaction.resolve("validation");
            ExternalPluginInstaller validator = new ExternalPluginInstaller(validationDir, limits, verifierResolver);
            PluginInstallResult validated = validator.install(packagePath, true,
                    origin != null ? origin : PluginPackageOrigin.localUpload());
            if (!validated.accepted() || validated.descriptor() == null || validated.installedPath() == null) {
                deleteRecursivelyQuietly(transaction);
                return new PreparedPluginTransaction(transactionId, validated, null, null, null, List.of());
            }

            PluginDescriptor descriptor = validated.descriptor();
            List<InstalledPlugin> installed = listInstalled();
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
                    deleteRecursivelyQuietly(transaction);
                    PluginInstallResult duplicate = new PluginInstallResult(PluginInstallOutcome.DUPLICATE,
                            descriptor, highest.path(), previousVersion,
                            List.of(descriptor.id() + " " + descriptor.version() + " already installed"));
                    return new PreparedPluginTransaction(transactionId, duplicate, null, null, null,
                            sameId.stream().map(InstalledPlugin::path).toList());
                } else if (allowDowngrade) {
                    outcome = PluginInstallOutcome.DOWNGRADED;
                } else {
                    deleteRecursivelyQuietly(transaction);
                    PluginInstallResult rejected = new PluginInstallResult(PluginInstallOutcome.DOWNGRADE_REJECTED,
                            descriptor, null, previousVersion, List.of("refusing to downgrade " + descriptor.id()
                            + " from " + previousVersion + " to " + descriptor.version() + " (force required)"));
                    return new PreparedPluginTransaction(transactionId, rejected, null, null, null,
                            sameId.stream().map(InstalledPlugin::path).toList());
                }
            }

            Files.createDirectories(transaction.resolve("new"));
            Path staged = transaction.resolve("new").resolve(validated.installedPath().getFileName());
            validator.provenanceStore.moveWithArtifact(validated.installedPath(), staged,
                    ExternalPluginInstaller::moveIntoPlace);
            deleteRecursivelyQuietly(validationDir);
            Path target = pluginsDir.resolve(staged.getFileName()).normalize();
            PluginInstallResult result = new PluginInstallResult(outcome, descriptor, target, previousVersion,
                    List.of(outcome + " " + descriptor.id() + " " + descriptor.version()));
            PreparedPluginTransaction prepared = new PreparedPluginTransaction(transactionId, result, transaction,
                    staged, target, Stream.concat(sameId.stream(), replaced.stream())
                    .map(InstalledPlugin::path).toList());
            writeManifest(prepared, PluginTransactionState.PREPARED, List.of());
            return prepared;
        } catch (IOException e) {
            return new PreparedPluginTransaction(UUID.randomUUID().toString(),
                    new PluginInstallResult(PluginInstallOutcome.FAILED, null, null, null,
                            List.of("failed to stage plugin transaction: " + e.getMessage())),
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
            List<Path> expected = verifyCurrentArtifactsExclusive(prepared);
            Files.createDirectories(pluginsDir);
            Path backupDir = prepared.transactionDirectory().resolve(BACKUP_SUBDIR);
            Files.createDirectories(backupDir);
            List<CommittedPluginTransaction.BackupArtifact> backups = new ArrayList<>();
            try {
                for (Path origin : expected) {
                    Path backup = backupDir.resolve(backups.size() + "-" + origin.getFileName());
                    backups.add(new CommittedPluginTransaction.BackupArtifact(origin, backup));
                }
                // 先持久化全部 origin -> backup 映射，再开始移动。进程在任一移动后崩溃时，
                // 启动恢复都能把已经存在的备份逐一放回原位。
                writeManifest(prepared, PluginTransactionState.PREPARED, backups);
                for (CommittedPluginTransaction.BackupArtifact backup : backups) {
                    moveArtifactWithSidecar(backup.origin(), backup.backup());
                }
                writeManifest(prepared, PluginTransactionState.OLD_ISOLATED, backups);
                moveArtifactWithSidecar(prepared.stagedArtifact(), prepared.target());
                writeManifest(prepared, PluginTransactionState.NEW_PLACED, backups);
                return new CommittedPluginTransaction(prepared, backups);
            } catch (IOException | RuntimeException failure) {
                deleteArtifactAndSidecar(prepared.target());
                restoreBackups(backups);
                throw failure;
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to commit plugin transaction " + prepared.transactionId(), e);
        } finally {
            installLock.unlock();
        }
    }

    /** 在静默和物理卸载前复核暂存期间安装态未变化；commit 内仍会再次复核。 */
    public void verifyCurrentArtifacts(PreparedPluginTransaction prepared) {
        if (prepared == null || !prepared.readyToCommit()) {
            throw new IllegalArgumentException("plugin transaction is not ready to verify");
        }
        installLock.lock();
        try {
            verifyCurrentArtifactsExclusive(prepared);
        } finally {
            installLock.unlock();
        }
    }

    private List<Path> verifyCurrentArtifactsExclusive(PreparedPluginTransaction prepared) {
        List<String> affectedIds = new ArrayList<>(prepared.result().descriptor().replaces());
        affectedIds.add(prepared.result().pluginId());
        List<Path> current = listInstalled().stream()
                .filter(plugin -> affectedIds.contains(plugin.id()))
                .map(InstalledPlugin::path).map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        List<Path> expected = prepared.expectedCurrentArtifacts().stream()
                .map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        if (!current.equals(expected)) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "installed plugin set changed while transaction was staged");
        }
        return expected;
    }

    /** 记录新 generation 已完成运行时激活。 */
    public void markActivated(CommittedPluginTransaction transaction) {
        installLock.lock();
        try {
            writeManifest(transaction.prepared(), PluginTransactionState.ACTIVATED, transaction.backups());
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist plugin activation", e);
        } finally {
            installLock.unlock();
        }
    }

    /** 提交完成：删除旧包 backup 与事务清单。 */
    public void completeTransaction(CommittedPluginTransaction transaction) {
        installLock.lock();
        try {
            writeManifest(transaction.prepared(), PluginTransactionState.COMMITTED, transaction.backups());
            deleteRecursivelyQuietly(transaction.prepared().transactionDirectory());
            deleteStagingRootIfEmpty();
        } catch (IOException e) {
            log.warn("Failed to finalize plugin transaction {}: {}",
                    transaction.prepared().transactionId(), e.toString());
        } finally {
            installLock.unlock();
        }
    }

    /** 删除新包并把 backup 原样恢复到旧规范路径。 */
    public boolean rollbackTransaction(CommittedPluginTransaction transaction) {
        installLock.lock();
        try {
            deleteArtifactAndSidecar(transaction.prepared().target());
            boolean restored = restoreBackups(transaction.backups());
            if (restored) {
                deleteRecursivelyQuietly(transaction.prepared().transactionDirectory());
                deleteStagingRootIfEmpty();
            }
            return restored;
        } catch (IOException e) {
            log.error("Failed to roll back plugin transaction {}: {}",
                    transaction.prepared().transactionId(), e.toString());
            return false;
        } finally {
            installLock.unlock();
        }
    }

    /** 放弃尚未提交的 staged artifact。 */
    public void discardPrepared(PreparedPluginTransaction prepared) {
        if (prepared != null && prepared.transactionDirectory() != null) {
            Path manifestPath = prepared.transactionDirectory().resolve(TRANSACTION_MANIFEST);
            if (Files.isRegularFile(manifestPath)) {
                try {
                    Properties manifest = readManifest(manifestPath);
                    int count = Integer.parseInt(manifest.getProperty("backup.count", "0"));
                    for (int i = 0; i < count; i++) {
                        String value = manifest.getProperty("backup." + i + ".path");
                        if (value != null && Files.exists(Path.of(value))) {
                            log.error("Keeping plugin transaction {} because an old artifact backup still exists: {}",
                                    prepared.transactionId(), value);
                            return;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    log.error("Keeping plugin transaction {} because its recovery manifest could not be verified: {}",
                            prepared.transactionId(), e.toString());
                    return;
                }
            }
            deleteRecursivelyQuietly(prepared.transactionDirectory());
            deleteStagingRootIfEmpty();
        }
    }

    /**
     * 启动扫描前恢复未完成事务：ACTIVATED 视为新包已成功运行并提交，其余状态优先恢复旧包。
     */
    public void recoverPendingTransactions() {
        installLock.lock();
        try {
            Path stagingRoot = pluginsDir.resolve(STAGING_DIR);
            if (!Files.isDirectory(stagingRoot)) {
                return;
            }
            try (Stream<Path> entries = Files.list(stagingRoot)) {
                for (Path transaction : entries.filter(Files::isDirectory).toList()) {
                    Path manifestPath = transaction.resolve(TRANSACTION_MANIFEST);
                    if (!Files.isRegularFile(manifestPath)) {
                        deleteRecursivelyQuietly(transaction);
                        continue;
                    }
                    Properties manifest = readManifest(manifestPath);
                    PluginTransactionState state = PluginTransactionState.valueOf(manifest.getProperty("state"));
                    if (state == PluginTransactionState.ACTIVATED || state == PluginTransactionState.COMMITTED) {
                        deleteRecursivelyQuietly(transaction);
                        continue;
                    }
                    String targetValue = manifest.getProperty("target");
                    if (targetValue != null && !targetValue.isBlank()) {
                        deleteArtifactAndSidecar(Path.of(targetValue));
                    }
                    int count = Integer.parseInt(manifest.getProperty("backup.count", "0"));
                    boolean restored = true;
                    for (int i = 0; i < count; i++) {
                        Path origin = Path.of(manifest.getProperty("backup." + i + ".origin"));
                        Path backup = Path.of(manifest.getProperty("backup." + i + ".path"));
                        if (Files.exists(backup)) {
                            try {
                                moveArtifactWithSidecar(backup, origin);
                            } catch (IOException e) {
                                restored = false;
                                log.error("Failed to recover plugin backup {}: {}", backup, e.toString());
                            }
                        }
                    }
                    if (restored) {
                        deleteRecursivelyQuietly(transaction);
                    }
                }
            }
            deleteStagingRootIfEmpty();
        } catch (IOException | RuntimeException e) {
            log.error("Failed to recover pending plugin transactions: {}", e.toString());
        } finally {
            installLock.unlock();
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
        List<InstalledPlugin> installedPlugins = listInstalled();
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
                    origin, verification);
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
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        List<InstalledPlugin> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(pluginsDir)) {
            for (Path path : entries.filter(ExternalPluginInstaller::isInstalledCandidate).sorted().toList()) {
                try {
                    PluginPackageInspection inspection = PluginPackageReader.inspect(path);
                    result.add(new InstalledPlugin(inspection.descriptor(), path));
                } catch (RuntimeException e) {
                    log.warn("Skipping unreadable plugin package {}: {}", path.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list plugins directory {}: {}", pluginsDir, e.toString());
        }
        return result;
    }

    /** 调用方已完成物理卸载后，事务化删除指定包的全部已安装 artifact。 */
    public boolean removeInstalled(String packageId) {
        installLock.lock();
        try {
            List<InstalledPlugin> matches = listInstalled().stream()
                    .filter(plugin -> Objects.equals(packageId, plugin.id())).toList();
            if (matches.isEmpty()) {
                return false;
            }
            Path transaction = pluginsDir.resolve(STAGING_DIR).resolve("remove-" + UUID.randomUUID());
            Path backupDir = transaction.resolve(BACKUP_SUBDIR);
            List<Backup> backups = new ArrayList<>();
            try {
                Files.createDirectories(backupDir);
                for (InstalledPlugin plugin : matches) {
                    Path backup = backupDir.resolve(backups.size() + "-" + plugin.path().getFileName());
                    backups.add(new Backup(plugin.path(), backup));
                }
                writeRemovalManifest(transaction, packageId, PluginTransactionState.PREPARED, backups);
                for (Backup backup : backups) {
                    moveArtifactWithSidecar(backup.origin(), backup.backup());
                }
                writeRemovalManifest(transaction, packageId, PluginTransactionState.COMMITTED, backups);
                deleteRecursivelyQuietly(transaction);
                deleteStagingRootIfEmpty();
                return true;
            } catch (IOException e) {
                restoreSuperseded(backups);
                throw new IllegalStateException("failed to remove installed plugin " + packageId, e);
            }
        } finally {
            installLock.unlock();
        }
    }

    private PluginInstallResult commit(Path packagePath, PluginPackageInspection inspection,
                                       PluginDescriptor descriptor, PluginInstallOutcome outcome,
                                       List<InstalledPlugin> supersededCandidates, Path target, String previousVersion,
                                       PluginPackageOrigin origin, VerificationResult verification)
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
            provenanceStore.write(stagedArtifact, origin, verification);

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
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static void writeManifest(PreparedPluginTransaction prepared, PluginTransactionState state,
                                      List<CommittedPluginTransaction.BackupArtifact> backups) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("transaction.id", prepared.transactionId());
        properties.setProperty("state", state.name());
        properties.setProperty("package.id", Objects.toString(prepared.result().pluginId(), ""));
        properties.setProperty("version", Objects.toString(prepared.result().version(), ""));
        properties.setProperty("target", prepared.target() != null
                ? prepared.target().toAbsolutePath().normalize().toString() : "");
        properties.setProperty("backup.count", Integer.toString(backups.size()));
        for (int i = 0; i < backups.size(); i++) {
            properties.setProperty("backup." + i + ".origin",
                    backups.get(i).origin().toAbsolutePath().normalize().toString());
            properties.setProperty("backup." + i + ".path",
                    backups.get(i).backup().toAbsolutePath().normalize().toString());
        }
        Files.createDirectories(prepared.transactionDirectory());
        Path manifest = prepared.transactionDirectory().resolve(TRANSACTION_MANIFEST);
        Path temporary = prepared.transactionDirectory().resolve(TRANSACTION_MANIFEST + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "PixivDownloader plugin transaction");
        }
        moveIntoPlace(temporary, manifest);
    }

    private static void writeRemovalManifest(Path transaction, String packageId, PluginTransactionState state,
                                             List<Backup> backups) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("transaction.id", transaction.getFileName().toString());
        properties.setProperty("state", state.name());
        properties.setProperty("package.id", Objects.toString(packageId, ""));
        properties.setProperty("version", "");
        properties.setProperty("target", "");
        properties.setProperty("backup.count", Integer.toString(backups.size()));
        for (int i = 0; i < backups.size(); i++) {
            properties.setProperty("backup." + i + ".origin",
                    backups.get(i).origin().toAbsolutePath().normalize().toString());
            properties.setProperty("backup." + i + ".path",
                    backups.get(i).backup().toAbsolutePath().normalize().toString());
        }
        Files.createDirectories(transaction);
        Path manifest = transaction.resolve(TRANSACTION_MANIFEST);
        Path temporary = transaction.resolve(TRANSACTION_MANIFEST + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "PixivDownloader plugin removal transaction");
        }
        moveIntoPlace(temporary, manifest);
    }

    private static Properties readManifest(Path manifest) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
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
     * 把一个被取代的旧同 id 包原子移动到隔离备份位置（同卷，保证原子移动）。包内可见的接缝：测试可覆盖它来模拟
     * 「旧包无法移除 / 隔离」的 IO 失败（跨平台难稳定复现的文件锁），生产实现就是一次同卷移动、不放大对外 API。
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
    private static void cleanupStaging(Path stagingRoot, Path staging, boolean backupsResolved) {
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

    private static String canonicalFileName(PluginDescriptor descriptor, PluginPackageFormat format) {
        String ext = format == PluginPackageFormat.SINGLE_JAR ? ".jar" : ".zip";
        return descriptor.id() + "-" + descriptor.version() + ext;
    }

    private static boolean isInstalledCandidate(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".")) {
            return false;
        }
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete staging entry {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean staging directory {}: {}", root, e.toString());
        }
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
