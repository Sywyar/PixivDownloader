package top.sywyar.pixivdownload.plugin.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 受信 catalog 插件包下载器：把一个 catalog 声明的版本包 {@link PluginCatalogPackage} 经 SSRF 安全的
 * {@link PluginCatalogHttpClient} 流式下载到<b>临时文件</b>，供 {@code ExternalPluginInstaller} 校验后落盘。它的 public
 * 方法<b>只接收 catalog 包对象、不接收裸 URL 字符串</b>——下载地址只来自受信清单。
 *
 * <h2>安全要点</h2>
 * <ul>
 *   <li>校验包元数据完整：{@code packageUrl} 非空、{@code expectedSize} &gt; 0 且 &le; 绝对上限、{@code sha256} 必填、
 *       URL 以 {@code .jar}/{@code .zip} 结尾——否则 {@link PluginCatalogErrorCode#INVALID_PACKAGE_METADATA}
 *       （或声明尺寸超限 {@link PluginCatalogErrorCode#DOWNLOAD_TOO_LARGE}）。</li>
 *   <li>下载流式上限 = {@code min(expectedSize, 绝对上限)}：超过即中止、删临时文件、
 *       {@link PluginCatalogErrorCode#DOWNLOAD_TOO_LARGE}。</li>
 *   <li>下载完成<b>不</b>在此重复算哈希——把临时文件 + 来源期望（大小 / sha256 / 签名）交
 *       {@code ExternalPluginInstaller.install(temp, false, PluginPackageOrigin.forTrustedCatalog(...))}，由既有
 *       {@code PluginPackageIntegrity} 做<b>权威</b>的大小 / sha256 / 签名（fail-closed）校验，单一权威、避免重复哈希。</li>
 *   <li>失败 / 异常都清理临时文件，不留半成品。</li>
 * </ul>
 */
public class PluginPackageDownloader {

    private static final Logger log = LoggerFactory.getLogger(PluginPackageDownloader.class);

    private final PluginCatalogHttpClient httpClient;
    private final long maxPackageBytes;
    private final Path tempDir;

    /**
     * @param httpClient      SSRF 安全 HTTP 客户端
     * @param maxPackageBytes 单包绝对字节上限（&le;0 取默认 100MB）
     * @param tempDir         临时下载目录（{@code null} → 系统临时目录；测试注入可控目录以断言清理）
     */
    public PluginPackageDownloader(PluginCatalogHttpClient httpClient, long maxPackageBytes, Path tempDir) {
        this.httpClient = httpClient;
        this.maxPackageBytes = maxPackageBytes > 0 ? maxPackageBytes : PluginCatalogProperties.DEFAULT_MAX_PACKAGE_BYTES;
        this.tempDir = tempDir;
    }

    /**
     * 下载一个受信包到临时文件并返回其路径。调用方安装完成 / 失败后<b>必须</b>删除返回的临时文件
     * （见 {@link PluginCatalogAcquisitionService}，于 {@code finally} 删除）。下载失败 / 超限时本方法已自行清理、不留半成品。
     */
    public Path downloadToTemp(PluginCatalogPackage pkg) {
        validateMetadata(pkg);
        // 规范化一次：与 PluginCatalogHttpClient.verifyUrlAllowed 内部的 trim 同口径，使后缀推断与流式下载用同一个值，
        // 避免「按原始串推后缀、按 trim 后的串下载」的不一致（catalog 元数据里首尾带空白的 packageUrl 不致误判）。
        String url = pkg.packageUrl().trim();
        long cap = Math.min(pkg.expectedSizeBytes(), maxPackageBytes);
        String suffix = packageSuffix(url); // 失败抛出在建临时文件之前，无残留
        Path temp;
        try {
            temp = tempDir != null
                    ? Files.createTempFile(tempDir, "plugin-catalog-", suffix)
                    : Files.createTempFile("plugin-catalog-", suffix);
        } catch (IOException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "failed to create temp file: " + e.getMessage());
        }
        try {
            httpClient.streamToFile(url, cap, temp);
            return temp;
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private void validateMetadata(PluginCatalogPackage pkg) {
        if (pkg == null || pkg.packageUrl() == null || pkg.packageUrl().isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INVALID_PACKAGE_METADATA, "missing package url");
        }
        if (pkg.expectedSizeBytes() == null || pkg.expectedSizeBytes() <= 0) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INVALID_PACKAGE_METADATA,
                    "missing or non-positive expectedSizeBytes");
        }
        if (pkg.expectedSizeBytes() > maxPackageBytes) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE,
                    "declared package size " + pkg.expectedSizeBytes() + " exceeds limit " + maxPackageBytes);
        }
        if (pkg.sha256() == null || pkg.sha256().isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INVALID_PACKAGE_METADATA, "missing sha256");
        }
    }

    /** 临时文件后缀（决定安装器的类型门）：URL 路径以 {@code .jar} / {@code .zip} 结尾分别取之，否则视为不支持的包。 */
    private static String packageSuffix(String url) {
        String path;
        try {
            String p = new URI(url).getPath();
            path = p != null ? p.toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            path = "";
        }
        if (path.endsWith(".jar")) {
            return ".jar";
        }
        if (path.endsWith(".zip")) {
            return ".zip";
        }
        throw new PluginCatalogException(PluginCatalogErrorCode.INVALID_PACKAGE_METADATA,
                "package url must end with .jar or .zip: " + url);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp plugin download {}: {}", path, e.toString());
        }
    }
}
