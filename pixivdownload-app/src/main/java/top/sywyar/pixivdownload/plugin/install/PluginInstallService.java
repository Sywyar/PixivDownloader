package top.sywyar.pixivdownload.plugin.install;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.management.PluginManagementService;

/**
 * 本地插件包安装后端服务（admin-grade、不依赖任何 UI）：把管理员上传的本地 {@code .jar} / {@code .zip} 插件包交给
 * 运行时安装器 {@link ExternalPluginInstaller} 安全装入 {@code plugins/} 安装目录，并把结果整理为结构化
 * {@link PluginInstallReport}（含依赖诊断）。它是 Web / GUI 安装入口共用的后端落点——上层不各自实现包读取 / 落盘。
 *
 * <h2>install 与运行期 load / start 的边界</h2>
 * Spring 运行时统一交给 {@link ExternalPluginLifecycleCoordinator}：安装器只负责包校验与文件事务，编排器负责静默、
 * 物理卸载、原子替换、重新加载和启动；激活失败时恢复旧包并报告回滚结果。所有生产入口都必须消费该统一回执。
 *
 * <p>上传字节先写入<b>系统临时文件</b>（文件名仅取上传名的 {@code .jar} / {@code .zip} 扩展名、<b>绝不</b>用上传名做
 * 路径分量，杜绝路径穿越），交安装器后于 {@code finally} 删除临时文件；安装器从临时文件复制出规范产物到安装目录的
 * 隔离暂存再原子落位，故临时文件位置不影响落盘原子性。本服务不触碰鉴权——HTTP 入口由 {@code AuthFilter} 按
 * {@code /api/plugins/** = ADMIN} 独立校验。
 */
@Service
public class PluginInstallService {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallService.class);
    static final int MAX_SIGNATURE_BYTES = 16 * 1024;
    private static final ObjectMapper SIGNATURE_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ExternalPluginLifecycleCoordinator coordinator;
    private final PluginDependencyResolver dependencyResolver;
    private final boolean developmentModeEnabled;

    @Autowired
    public PluginInstallService(ExternalPluginLifecycleCoordinator coordinator,
                                PluginDependencyResolver dependencyResolver) {
        this(coordinator, dependencyResolver, PluginDevelopmentArtifacts.enabled());
    }

    public PluginInstallService(ExternalPluginLifecycleCoordinator coordinator,
                                PluginDependencyResolver dependencyResolver,
                                boolean developmentModeEnabled) {
        this.coordinator = coordinator;
        this.dependencyResolver = dependencyResolver;
        this.developmentModeEnabled = developmentModeEnabled;
    }

    /**
     * 安装一个上传的本地插件包。空 / 缺失上传 → {@link PluginInstallOutcome#REJECTED_EMPTY}；临时落盘失败（服务端
     * IO）→ {@link PluginInstallOutcome#FAILED}；其余结局（含各类拒绝）由安装器据包内容裁定。
     *
     * @param file           上传的 {@code .jar} / {@code .zip} 插件包
     * @param allowDowngrade 是否允许覆盖更高版本（force；默认 false 时低版本覆盖高版本 →
     *                       {@link PluginInstallOutcome#DOWNGRADE_REJECTED}）
     */
    public PluginInstallReport install(MultipartFile file, boolean allowDowngrade) {
        return install(file, null, allowDowngrade);
    }

    /**
     * 安装本地插件包及其可选 detached 签名。签名用于发布者身份连续性；正式运行时未签名包以精确 SHA-256
     * 确认授予执行信任，显式插件开发模式不继承生产信任。
     */
    public PluginInstallReport install(MultipartFile file, MultipartFile signatureFile, boolean allowDowngrade) {
        return install(file, signatureFile, allowDowngrade, null);
    }

    public PluginInstallReport install(
            MultipartFile file,
            MultipartFile signatureFile,
            boolean allowDowngrade,
            String confirmedTrustSha256) {
        if (file == null || file.isEmpty()) {
            return terminal(PluginInstallOutcome.REJECTED_EMPTY, "no plugin package uploaded");
        }
        SignatureMetadata signature;
        if (signatureFile == null) {
            signature = null;
        } else {
            try {
                signature = readSignature(signatureFile);
            } catch (IOException | RuntimeException e) {
                return terminal(PluginInstallOutcome.REJECTED_INTEGRITY,
                        "detached signature is malformed or exceeds the size limit");
            }
        }
        Path temp;
        try {
            temp = Files.createTempFile("plugin-upload-", uploadSuffix(file));
        } catch (IOException e) {
            log.error("Failed to create temp file for uploaded plugin package: {}", e.toString());
            return terminal(PluginInstallOutcome.FAILED, "failed to stage uploaded package: " + e.getMessage());
        }
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Failed to write uploaded plugin package to temp file: {}", e.toString());
                return terminal(PluginInstallOutcome.FAILED, "failed to stage uploaded package: " + e.getMessage());
            }
            PluginPackageOrigin origin = signature != null
                    ? PluginPackageOrigin.localUpload(signature, confirmedTrustSha256)
                    : developmentModeEnabled
                            ? PluginPackageOrigin.localUpload()
                            : PluginPackageOrigin.localUnsignedUpload(confirmedTrustSha256);
            return toReport(coordinator.installOrUpdate(temp, allowDowngrade, origin));
        } finally {
            deleteQuietly(temp);
        }
    }

    private static SignatureMetadata readSignature(MultipartFile signatureFile) throws IOException {
        if (signatureFile.isEmpty() || signatureFile.getSize() > MAX_SIGNATURE_BYTES) {
            throw new IOException("detached signature is empty or too large");
        }
        byte[] bytes;
        try (InputStream in = signatureFile.getInputStream()) {
            bytes = in.readNBytes(MAX_SIGNATURE_BYTES + 1);
        }
        if (bytes.length > MAX_SIGNATURE_BYTES) {
            throw new IOException("detached signature is too large");
        }
        return SIGNATURE_MAPPER.readValue(bytes, SignatureMetadata.class);
    }

    /**
     * 安装一个已下载到本地、来源为<b>受信 catalog</b>（{@link PluginPackageOrigin#forTrustedCatalog}）的插件包文件：交安装器
     * 在落盘前做完整性校验（大小 / sha256 / 签名 fail-closed）+ 全部结构 / 兼容 / Zip Slip / 重复升降级校验，整理为
     * {@link PluginInstallReport}。<b>不</b>删除入参文件——临时文件由调用方（受信 catalog 获取流程）拥有并清理。供
     * {@code PluginCatalogAcquisitionService} 复用本服务的报告构建（依赖诊断等），不另写一套。
     *
     * @param packageFile    已下载到本地的 {@code .jar} / {@code .zip} 插件包路径
     * @param allowDowngrade 是否允许覆盖更高版本（force）
     * @param origin         包来源 + 完整性期望（受信 catalog 携带期望大小 / sha256 / 签名）
     */
    public PluginInstallReport installTrustedFile(Path packageFile, boolean allowDowngrade, PluginPackageOrigin origin) {
        return toReport(coordinator.installOrUpdate(packageFile, allowDowngrade, origin));
    }

    private PluginInstallReport toReport(PluginActivationResult activation) {
        PluginInstallResult result = activation.installResult();
        PluginDescriptor descriptor = result.descriptor();
        // 安装路径仅对进程重启策略延迟激活；BACKEND_RESTART 只约束管理页启停，安装仍会即时激活。
        boolean effectiveAfterRestart = result.accepted()
                && descriptor != null
                && descriptor.lifecyclePolicy().requiresProcessRestart();
        List<PluginDependencyProblem> problems = activation.dependencyProblems().isEmpty()
                ? dependencyResolver.installedProblems(descriptor)
                : activation.dependencyProblems();
        return new PluginInstallReport(
                result.outcome(), result.accepted(), effectiveAfterRestart,
                result.pluginId(), result.version(), result.previousVersion(),
                declaredDependencies(descriptor), unsatisfiedDependencies(problems), problems, result.messages(),
                activation.transactionId(), activation.activated(), activation.rolledBack(),
                activation.rollbackVersion(), activation.operation(), activation.runtimePhase(),
                activation.recoveryBlocked(),
                activation.activated() && result.previousVersion() != null
                        && result.outcome() != PluginInstallOutcome.DUPLICATE,
                List.of(),
                result.trustRequirement());
    }

    /** 描述符声明的插件间依赖投影（描述符不可读时为空列表）。 */
    private static List<PluginDependencyView> declaredDependencies(PluginDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        return descriptor.dependencies().stream().map(PluginDependencyView::from).toList();
    }

    /** 未满足依赖 id 摘要（结构化详情见 {@code dependencyProblems}）。 */
    private static List<String> unsatisfiedDependencies(List<PluginDependencyProblem> problems) {
        return problems.stream().map(PluginDependencyProblem::pluginId).distinct().toList();
    }

    /**
     * 临时文件后缀：仅从上传文件名取受支持的扩展名（{@code .jar} / {@code .zip}，大小写不敏感），其余一律 {@code .bin}
     * （安装器据此返回「不支持的包类型」{@link PluginInstallOutcome#REJECTED_MALFORMED}）。<b>只取扩展名、绝不</b>用上传
     * 文件名做路径分量，杜绝路径穿越。
     */
    private static String uploadSuffix(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar")) {
                return ".jar";
            }
            if (lower.endsWith(".zip")) {
                return ".zip";
            }
        }
        return ".bin";
    }

    /** 一个无描述符的终态结果（上传缺失 / 临时落盘失败）：无 id / 版本 / 依赖，仅带 outcome 与一条诊断说明。 */
    private static PluginInstallReport terminal(PluginInstallOutcome outcome, String diagnostic) {
        return new PluginInstallReport(outcome, outcome.accepted(), outcome.accepted(),
                null, null, null, List.of(), List.of(), List.of(diagnostic));
    }

    private void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Failed to delete temporary uploaded plugin package {}: {}", temp, e.toString());
        }
    }
}
