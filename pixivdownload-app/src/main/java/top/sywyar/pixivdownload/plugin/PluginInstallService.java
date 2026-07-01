package top.sywyar.pixivdownload.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.plugin.PluginManagementService.PluginDependencyView;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.policy.StartupOnlyPlugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地插件包安装后端服务（admin-grade、不依赖任何 UI）：把管理员上传的本地 {@code .jar} / {@code .zip} 插件包交给
 * 运行时安装器 {@link ExternalPluginInstaller} 安全装入 {@code plugins/} 安装目录，并把结果整理为结构化
 * {@link PluginInstallReport}（含依赖诊断）。它是 Web / GUI 安装入口共用的后端落点——上层不各自实现包读取 / 落盘。
 *
 * <h2>install 与运行期 load / start 的边界</h2>
 * Spring 运行时统一交给 {@link ExternalPluginLifecycleCoordinator}：安装器只负责包校验与文件事务，编排器负责静默、
 * 物理卸载、原子替换、重新加载和启动；激活失败时恢复旧包并报告回滚结果。仅兼容测试构造器会直接调用安装器。
 *
 * <p>上传字节先写入<b>系统临时文件</b>（文件名仅取上传名的 {@code .jar} / {@code .zip} 扩展名、<b>绝不</b>用上传名做
 * 路径分量，杜绝路径穿越），交安装器后于 {@code finally} 删除临时文件；安装器从临时文件复制出规范产物到安装目录的
 * 隔离暂存再原子落位，故临时文件位置不影响落盘原子性。本服务不触碰鉴权——HTTP 入口由 {@code AuthFilter} 按
 * {@code /api/plugins/** = ADMIN} 独立校验。
 */
@Service
public class PluginInstallService {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallService.class);

    private final ExternalPluginInstaller installer;
    private final ExternalPluginLifecycleCoordinator coordinator;

    public PluginInstallService(ExternalPluginInstaller installer) {
        this.installer = installer;
        this.coordinator = null;
    }

    @Autowired
    public PluginInstallService(ExternalPluginInstaller installer,
                                ExternalPluginLifecycleCoordinator coordinator) {
        this.installer = installer;
        this.coordinator = coordinator;
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
        if (file == null || file.isEmpty()) {
            return terminal(PluginInstallOutcome.REJECTED_EMPTY, "no plugin package uploaded");
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
            return coordinator != null
                    ? toReport(coordinator.installOrUpdate(temp, allowDowngrade, PluginPackageOrigin.localUpload()))
                    : toReport(installer.install(temp, allowDowngrade));
        } finally {
            deleteQuietly(temp);
        }
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
        return coordinator != null
                ? toReport(coordinator.installOrUpdate(packageFile, allowDowngrade, origin))
                : toReport(installer.install(packageFile, allowDowngrade, origin));
    }

    private PluginInstallReport toReport(PluginActivationResult activation) {
        PluginInstallResult result = activation.installResult();
        PluginDescriptor descriptor = result.descriptor();
        boolean startupOnly = result.accepted() && StartupOnlyPlugins.isStartupOnly(result.pluginId());
        return new PluginInstallReport(
                result.outcome(), result.accepted(), startupOnly,
                result.pluginId(), result.version(), result.previousVersion(),
                declaredDependencies(descriptor), unsatisfiedDependencies(descriptor), result.messages(),
                activation.transactionId(), activation.activated(), activation.rolledBack(),
                activation.rollbackVersion(), activation.operation(), activation.runtimePhase(),
                activation.activated() && result.previousVersion() != null
                        && result.outcome() != PluginInstallOutcome.DUPLICATE);
    }

    private PluginInstallReport toReport(PluginInstallResult result) {
        PluginDescriptor descriptor = result.descriptor();
        return new PluginInstallReport(
                result.outcome(),
                result.accepted(),
                result.accepted(),
                result.pluginId(),
                result.version(),
                result.previousVersion(),
                declaredDependencies(descriptor),
                unsatisfiedDependencies(descriptor),
                result.messages());
    }

    /** 描述符声明的插件间依赖投影（描述符不可读时为空列表）。 */
    private static List<PluginDependencyView> declaredDependencies(PluginDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        return descriptor.dependencies().stream().map(PluginDependencyView::from).toList();
    }

    /**
     * 依赖诊断（建议性、<b>不</b>阻断安装）：描述符声明的<b>非可选</b>依赖里，当前既不是内置插件
     * （{@link BuiltInPlugins#isBuiltIn}）、也不在 {@code plugins/} 安装目录中（{@link ExternalPluginInstaller#listInstalled()}）
     * 的那些 id。插件框架在<b>加载期</b>解析依赖，故安装期缺依赖不拒绝（管理员可随后补装），仅作诊断提示。
     */
    private List<String> unsatisfiedDependencies(PluginDescriptor descriptor) {
        if (descriptor == null || descriptor.dependencies().isEmpty()) {
            return List.of();
        }
        Set<String> installedIds = new HashSet<>();
        for (InstalledPlugin installed : installer.listInstalled()) {
            installedIds.add(installed.id());
        }
        List<String> unsatisfied = new ArrayList<>();
        for (PluginDependencyRef dependency : descriptor.dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            String depId = dependency.pluginId();
            if (depId == null || BuiltInPlugins.isBuiltIn(depId) || installedIds.contains(depId)) {
                continue;
            }
            unsatisfied.add(depId);
        }
        return List.copyOf(unsatisfied);
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
