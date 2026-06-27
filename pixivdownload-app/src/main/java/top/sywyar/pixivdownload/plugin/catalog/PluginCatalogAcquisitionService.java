package top.sywyar.pixivdownload.plugin.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginInstallService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 受信 catalog 安装编排：解析出目标 {@link PluginRepository} → 用<b>同一仓库</b>拉清单、按 {@code pluginId} + {@code version}
 * 选包 → 经 {@link PluginPackageDownloader} 按<b>该仓库</b>代理策略 / 超时 / 大小上限装配的 SSRF 安全客户端下载到临时文件 →
 * 交 {@link PluginInstallService} 以受信来源（{@link PluginPackageOrigin#forTrustedCatalog}）做完整性 + 结构 + 兼容校验并
 * 安全落盘 → 删除临时文件。<b>不接受任意 URL</b>——下载地址只来自受信清单中按 id+version 选出的包。清单读取与包下载<b>同源
 * 同仓库</b>（不存在「下载走全局严格客户端旁路」）。落盘后<b>不</b>热加载，重启后由常规扫描发现（与本地上传安装边界一致）。
 */
@Service
public class PluginCatalogAcquisitionService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogAcquisitionService.class);

    private final PluginCatalogService catalogService;
    private final PluginPackageDownloader downloader;
    private final PluginInstallService installService;

    public PluginCatalogAcquisitionService(PluginCatalogService catalogService,
                                           PluginPackageDownloader downloader,
                                           PluginInstallService installService) {
        this.catalogService = catalogService;
        this.downloader = downloader;
        this.installService = installService;
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
        PluginCatalogEntry entry = manifest.findEntry(pluginId).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.UNKNOWN_PLUGIN, pluginId, version,
                        "plugin not found in catalog: " + pluginId));
        PluginCatalogPackage pkg = entry.findPackage(version).orElseThrow(() ->
                new PluginCatalogException(PluginCatalogErrorCode.VERSION_NOT_FOUND, pluginId, version,
                        "version not found in catalog: " + pluginId + " " + version));

        // throws PROXY_POLICY_UNSUPPORTED / INSECURE_URL / BLOCKED_ADDRESS / TOO_LARGE / FAILED / INVALID
        Path temp = downloader.downloadToTemp(repository, pkg);
        try {
            PluginPackageOrigin origin = PluginPackageOrigin.forTrustedCatalog(
                    pkg.expectedSizeBytes(), pkg.sha256(), pkg.signature());
            return installService.installTrustedFile(temp, false, origin);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Failed to delete catalog download temp {}: {}", temp, e.toString());
        }
    }
}
