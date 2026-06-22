package top.sywyar.pixivdownload.plugin.runtime.install;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

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
import java.util.stream.Stream;

/**
 * 外置插件安装器：把一个 {@code .zip} / {@code .jar} 安装包安全地装入运行时插件目录
 * （{@code RuntimeFiles.pluginsDirectory()}，与 PF4J 扫描的目录同一处）。<b>不</b>污染 app classpath、<b>不</b>写源码目录、
 * <b>不</b>做热加载——落盘后于下次启动 / 下次扫描由 {@code PluginRuntimeManager} 发现并加载。
 *
 * <h2>安装流程（原子、失败不留半成品）</h2>
 * <ol>
 *   <li><b>检视</b>：{@link PluginPackageReader#inspect} 读出布局 + 包级描述符；非法包（空 / 缺描述符 / 歧义 / 损坏）
 *       直接返回对应拒绝结果，<b>零落盘</b>。</li>
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
 */
public class ExternalPluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginInstaller.class);

    /** 安装目录内的隐藏暂存子目录名（以 {@code .} 开头，PF4J 扫描会跳过它）。 */
    static final String STAGING_DIR = ".staging";

    /** 暂存目录内存放「被取代旧包隔离备份」的子目录名。 */
    private static final String BACKUP_SUBDIR = "removed";

    private final Path pluginsDir;

    public ExternalPluginInstaller(Path pluginsDir) {
        if (pluginsDir == null) {
            throw new IllegalArgumentException("pluginsDir must not be null");
        }
        this.pluginsDir = pluginsDir;
    }

    /** 配置的安装目录（未规范化）。 */
    public Path pluginsDirectory() {
        return pluginsDir;
    }

    /** 安装一个插件包，默认<b>拒绝降级</b>（已存在更高版本则 {@code DOWNGRADE_REJECTED}）。 */
    public PluginInstallResult install(Path packagePath) {
        return install(packagePath, false);
    }

    /**
     * 安装一个插件包。
     *
     * @param packagePath    {@code .zip} / {@code .jar} 安装包路径
     * @param allowDowngrade 是否允许覆盖更高版本（force；仅内部参数，无 UI）
     */
    public PluginInstallResult install(Path packagePath, boolean allowDowngrade) {
        if (packagePath == null || !Files.isRegularFile(packagePath)) {
            return rejected(PluginInstallOutcome.REJECTED_EMPTY, "package file not found: " + packagePath);
        }
        String lower = packagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean isZip = lower.endsWith(".zip");
        if (!isZip && !lower.endsWith(".jar")) {
            return rejected(PluginInstallOutcome.REJECTED_MALFORMED,
                    "unsupported package type (expected .zip or .jar): " + packagePath.getFileName());
        }

        // 1. 检视：读布局 + 包级描述符
        PluginPackageInspection inspection;
        try {
            inspection = PluginPackageReader.inspect(packagePath);
        } catch (PluginPackageException e) {
            return rejected(mapReason(e.reason()), e.getMessage());
        }
        PluginDescriptor descriptor = inspection.descriptor();

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

        // 4. Zip Slip 校验（仅 .zip：PF4J 后续会解压它）
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
        List<InstalledPlugin> sameId = new ArrayList<>();
        for (InstalledPlugin installed : listInstalled()) {
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
            return new PluginInstallResult(PluginInstallOutcome.DUPLICATE, descriptor, sameId.get(0).path(),
                    previousVersion, List.of(descriptor.id() + " " + descriptor.version() + " already installed"));
        }

        // 6. 提交（原子、失败清暂存）
        try {
            return commit(packagePath, inspection, descriptor, outcome, sameId, target, previousVersion);
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

    private PluginInstallResult commit(Path packagePath, PluginPackageInspection inspection,
                                       PluginDescriptor descriptor, PluginInstallOutcome outcome,
                                       List<InstalledPlugin> sameId, Path target, String previousVersion)
            throws IOException {
        Files.createDirectories(pluginsDir); // 目录创建归安装流程
        Path stagingRoot = pluginsDir.resolve(STAGING_DIR);
        Path staging = stagingRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(staging);

        // 本次安装必须让安装目录里同 id 旧包从可识别文件中消失（规范目标自身除外）——纳入提交事务、失败可回滚
        List<InstalledPlugin> superseded = supersededExcluding(sameId, target);
        Path backupDir = staging.resolve(BACKUP_SUBDIR);
        List<Backup> backups = new ArrayList<>();
        List<String> removedNames = new ArrayList<>();
        boolean backupsResolved = false; // 备份已「随提交丢弃」或「随回滚还原」，可安全清理
        try {
            Path stagedArtifact = staging.resolve(target.getFileName().toString());
            produceArtifact(packagePath, inspection, stagedArtifact);

            // 1. 把被取代旧包原子移入隔离备份；任一失败 → 回滚已隔离者、返回 FAILED，绝不放置新包
            if (!superseded.isEmpty()) {
                Files.createDirectories(backupDir);
            }
            for (InstalledPlugin old : superseded) {
                Path origin = old.path();
                Path backup = backupDir.resolve(backups.size() + "-" + origin.getFileName());
                try {
                    isolateSuperseded(origin, backup);
                } catch (IOException e) {
                    backupsResolved = restoreSuperseded(backups);
                    log.error("Aborting install of {} {}: cannot isolate superseded {}: {}",
                            descriptor.id(), descriptor.version(), origin.getFileName(), e.toString());
                    return new PluginInstallResult(PluginInstallOutcome.FAILED, descriptor, null, previousVersion,
                            List.of("install aborted: cannot remove superseded package "
                                    + origin.getFileName() + " (" + e.getMessage() + ")"));
                }
                backups.add(new Backup(origin, backup));
                removedNames.add(origin.getFileName().toString());
            }

            // 2. 放置新包到最终目标；失败 → 还原被取代旧包、返回 FAILED（尽量保持原安装状态）
            try {
                moveIntoPlace(stagedArtifact, target);
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
        moveIntoPlace(origin, backup);
    }

    /**
     * 回滚：把已隔离的旧包从备份移回原位。全部成功返回 {@code true}；任一失败记录并返回 {@code false}
     * （此时备份仍是该旧包的唯一副本，不可在清理时删除）。
     */
    private static boolean restoreSuperseded(List<Backup> backups) {
        boolean allRestored = true;
        for (Backup backup : backups) {
            try {
                moveIntoPlace(backup.backup(), backup.origin());
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
        };
    }

    private static PluginInstallResult rejected(PluginInstallOutcome outcome, String message) {
        return new PluginInstallResult(outcome, null, null, null, List.of(Objects.toString(message, "")));
    }
}
