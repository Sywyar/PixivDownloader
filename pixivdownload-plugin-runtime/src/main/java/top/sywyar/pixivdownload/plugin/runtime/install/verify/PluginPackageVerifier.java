package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;

/**
 * 外置插件包资源规模安全扫描（防 Zip Bomb / 解压资源耗尽）。在<b>任何解压 / 落盘之前</b>对归档做一次只读扫描，
 * 任一规模超出 {@link PluginPackageLimits} 即抛 {@link PluginPackageException.Reason#TOO_LARGE} 拒绝整包。
 *
 * <h2>覆盖项</h2>
 * <ul>
 *   <li><b>归档体积</b>：磁盘文件大小不超过 {@link PluginPackageLimits#maxArchiveBytes()}；</li>
 *   <li><b>entry 数量</b>：不超过 {@link PluginPackageLimits#maxEntries()}（含目录条目）；</li>
 *   <li><b>单 entry 解压字节</b>：不超过 {@link PluginPackageLimits#maxEntryUncompressedBytes()}——同时覆盖 single-jar
 *       形态 zip 内那个 inner jar（它是外层 zip 的一个 entry）；</li>
 *   <li><b>总解压字节</b>：所有 entry 解压字节之和不超过 {@link PluginPackageLimits#maxTotalUncompressedBytes()}；</li>
 *   <li><b>压缩比</b>：较大 entry 的解压 / 压缩比不超过 {@link PluginPackageLimits#maxCompressionRatio()}。</li>
 * </ul>
 *
 * <h2>不信任 header</h2>
 * 解压字节按从 {@link ZipInputStream} <b>实际读取</b>的字节累计，<b>从不</b>读取 {@code ZipEntry.getSize()}
 * （header 声明可缺失或被伪造）。采用流式 {@code ZipInputStream}（逐 entry 读本地头、可在超限时<b>提前中止</b>），
 * 而非 {@code ZipFile}（一次性加载中央目录），故 entry 数量炸弹也在累计到上限时即被截断。
 *
 * <p>对 {@code .zip} 与 {@code .jar} 同等扫描（{@code .jar} 也是 zip 容器）。纯读取、不写盘、不分类布局
 * （布局识别仍归 {@link PluginPackageReader}）。
 */
public final class PluginPackageVerifier {

    /** 压缩比检查的地板阈值：只对解压后达到该字节数的 entry 应用压缩比上限，避免误伤压缩开销占比高的小文件。 */
    private static final long COMPRESSION_RATIO_FLOOR_BYTES = 64L * 1024;

    private PluginPackageVerifier() {
    }

    /**
     * 扫描归档资源规模。任一项超出 {@code limits} 抛 {@link PluginPackageException.Reason#TOO_LARGE}；不是合法 zip
     * 抛 {@link PluginPackageException.Reason#MALFORMED}。只读，不写盘。
     */
    public static void verify(Path archive, PluginPackageLimits limits) {
        long archiveBytes;
        try {
            archiveBytes = Files.size(archive);
        } catch (IOException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "failed to stat package for safety scan: " + e.getMessage(), e);
        }
        if (archiveBytes > limits.maxArchiveBytes()) {
            throw tooLarge("package archive too large: " + archiveBytes + " bytes (limit "
                    + limits.maxArchiveBytes() + ")");
        }

        long totalUncompressed = 0;
        int entryCount = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > limits.maxEntries()) {
                    throw tooLarge("too many zip entries (limit " + limits.maxEntries() + ")");
                }
                long entryUncompressed = 0;
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    entryUncompressed += read;
                    if (entryUncompressed > limits.maxEntryUncompressedBytes()) {
                        throw tooLarge("zip entry too large when decompressed: " + entry.getName()
                                + " exceeds " + limits.maxEntryUncompressedBytes() + " bytes");
                    }
                    totalUncompressed += read;
                    if (totalUncompressed > limits.maxTotalUncompressedBytes()) {
                        throw tooLarge("total decompressed size exceeds " + limits.maxTotalUncompressedBytes()
                                + " bytes");
                    }
                }
                assertCompressionRatio(entry, entryUncompressed, limits);
                zis.closeEntry();
            }
        } catch (ZipException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "not a valid zip package: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "failed to read package for safety scan: " + e.getMessage(), e);
        }
    }

    /**
     * 压缩比检查：仅对解压字节达到地板阈值的较大 entry 生效（{@code ZipInputStream} 读完该 entry 后
     * {@code getCompressedSize()} 才反映真实压缩字节）。压缩字节不可知（{@code <= 0}）或 entry 较小时跳过。
     */
    private static void assertCompressionRatio(ZipEntry entry, long uncompressed, PluginPackageLimits limits) {
        if (uncompressed < COMPRESSION_RATIO_FLOOR_BYTES) {
            return;
        }
        long compressed = entry.getCompressedSize();
        if (compressed <= 0) {
            return;
        }
        if (uncompressed / compressed > limits.maxCompressionRatio()) {
            throw tooLarge("zip entry compression ratio too high: " + entry.getName()
                    + " (" + uncompressed + "/" + compressed + " exceeds " + limits.maxCompressionRatio() + ")");
        }
    }

    private static PluginPackageException tooLarge(String message) {
        return new PluginPackageException(PluginPackageException.Reason.TOO_LARGE, message);
    }
}
