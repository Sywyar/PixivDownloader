package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;

import java.util.Objects;
import java.util.Properties;

/**
 * 外置插件包安装期的资源规模安全上限（防 Zip Bomb / 解压资源耗尽）。{@link PluginPackageVerifier} 据此扫描归档，
 * {@link PluginPackageReader} 据 {@link #maxDescriptorBytes()} 限制描述符读取字节。超限一律拒绝整包
 * （{@link PluginInstallOutcome#REJECTED_TOO_LARGE}），<b>零落盘</b>。
 *
 * <h2>与 Web 上传上限的关系</h2>
 * 本类是<b>安装器侧</b>的上限，独立于 Web 上传上限（{@code spring.servlet.multipart.max-file-size}，本地上传走
 * multipart、当前 10MB）。本地上传时 multipart 上限是更紧的有效闸门；安装器上限是更高的硬兜底，并覆盖<b>非
 * multipart</b> 的来源（如后续受信 catalog 元数据驱动的安装管线，见 {@link PluginPackageSource}）。
 * 二者刻意分开命名：安装器上限不得低于 Web 上传上限，以免出现「multipart 放行、安装器更小」的反直觉裂缝。
 *
 * <h2>不信任 header 声明</h2>
 * 解压字节计数必须按 {@link PluginPackageVerifier} 实际读取的字节累计，<b>不能只信</b> {@code ZipEntry.getSize()}
 * （header 可缺失 / 可伪造）。{@link #maxEntryUncompressedBytes()} 同时覆盖 single-jar 形态 zip 内那个 inner jar
 * 的解压字节（inner jar 是外层 zip 的一个 entry）。
 *
 * @param maxArchiveBytes            归档文件磁盘体积上限（字节，{@code .zip} / {@code .jar} 同等适用）
 * @param maxEntries                 归档内 entry 数量上限（含目录条目）
 * @param maxTotalUncompressedBytes  全部 entry 实际解压字节之和的上限
 * @param maxEntryUncompressedBytes  单个 entry 实际解压字节上限（含 single-jar zip 内的 inner jar）
 * @param maxDescriptorBytes         {@code plugin.properties} 描述符读取字节上限
 * @param maxCompressionRatio        单个 entry 的解压 / 压缩比上限（仅对超过内部地板阈值的较大 entry 生效，避免误伤小文件）
 * @param maxEntryNameLength         单个 entry 路径名最大字符数
 * @param maxEntryDepth              单个 entry 最大目录层级
 */
public record PluginPackageLimits(
        long maxArchiveBytes,
        int maxEntries,
        long maxTotalUncompressedBytes,
        long maxEntryUncompressedBytes,
        long maxDescriptorBytes,
        long maxCompressionRatio,
        int maxEntryNameLength,
        int maxEntryDepth) {

    public static final String PROPERTY_PREFIX = "pixivdownload.plugin.package.";

    /** 默认安装器归档体积上限：192 MiB（按当前最大官方 Compose 最终产物保留约 50% 余量）。 */
    public static final long DEFAULT_MAX_ARCHIVE_BYTES = 192L * 1024 * 1024;
    /** 默认 entry 数量上限：48000（按当前最大官方 Compose 最终产物保留约 80% 余量）。 */
    public static final int DEFAULT_MAX_ENTRIES = 48_000;
    /** 默认总解压字节上限：672 MiB（按当前最大官方 Compose 最终产物保留约 50% 余量）。 */
    public static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES = 672L * 1024 * 1024;
    /** 默认单 entry 解压字节上限：64 MiB（含 inner jar）。 */
    public static final long DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    /** 默认描述符读取字节上限：1 MiB（{@code plugin.properties} 实际仅数百字节）。 */
    public static final long DEFAULT_MAX_DESCRIPTOR_BYTES = 1L * 1024 * 1024;
    /** 默认单 entry 压缩比上限：200（已压缩的 jar / class 远低于此；只对较大 entry 生效）。 */
    public static final long DEFAULT_MAX_COMPRESSION_RATIO = 200L;
    /** 默认 entry 路径名上限：1024 字符。 */
    public static final int DEFAULT_MAX_ENTRY_NAME_LENGTH = 1_024;
    /** 默认 entry 目录深度上限：64 层。 */
    public static final int DEFAULT_MAX_ENTRY_DEPTH = 64;

    /** 保留既有调用方的六参数构造语义，并补入默认路径上限。 */
    public PluginPackageLimits(long maxArchiveBytes,
                               int maxEntries,
                               long maxTotalUncompressedBytes,
                               long maxEntryUncompressedBytes,
                               long maxDescriptorBytes,
                               long maxCompressionRatio) {
        this(maxArchiveBytes, maxEntries, maxTotalUncompressedBytes, maxEntryUncompressedBytes,
                maxDescriptorBytes, maxCompressionRatio,
                DEFAULT_MAX_ENTRY_NAME_LENGTH, DEFAULT_MAX_ENTRY_DEPTH);
    }

    public PluginPackageLimits {
        requirePositive("maxArchiveBytes", maxArchiveBytes);
        requirePositive("maxEntries", maxEntries);
        requirePositive("maxTotalUncompressedBytes", maxTotalUncompressedBytes);
        requirePositive("maxEntryUncompressedBytes", maxEntryUncompressedBytes);
        requirePositive("maxDescriptorBytes", maxDescriptorBytes);
        requirePositive("maxCompressionRatio", maxCompressionRatio);
        requirePositive("maxEntryNameLength", maxEntryNameLength);
        requirePositive("maxEntryDepth", maxEntryDepth);
    }

    /**
     * 安装器安全上限。每项都可用 {@value #PROPERTY_PREFIX} 前缀的同名 kebab-case JVM system property 覆盖；
     * 缺失时使用安全默认值，非法或非正数值会在运行时初始化时明确拒绝。
     */
    public static PluginPackageLimits defaults() {
        return fromProperties(System.getProperties());
    }

    static PluginPackageLimits fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return new PluginPackageLimits(
                configuredLong(properties, "max-archive-bytes", DEFAULT_MAX_ARCHIVE_BYTES),
                configuredInt(properties, "max-entries", DEFAULT_MAX_ENTRIES),
                configuredLong(properties, "max-total-uncompressed-bytes", DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES),
                configuredLong(properties, "max-entry-uncompressed-bytes", DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES),
                configuredLong(properties, "max-descriptor-bytes", DEFAULT_MAX_DESCRIPTOR_BYTES),
                configuredLong(properties, "max-compression-ratio", DEFAULT_MAX_COMPRESSION_RATIO),
                configuredInt(properties, "max-entry-name-length", DEFAULT_MAX_ENTRY_NAME_LENGTH),
                configuredInt(properties, "max-entry-depth", DEFAULT_MAX_ENTRY_DEPTH));
    }

    private static int configuredInt(Properties properties, String suffix, int fallback) {
        long value = configuredLong(properties, suffix, fallback);
        if (value > Integer.MAX_VALUE) {
            throw invalidProperty(suffix, Long.toString(value), null);
        }
        return (int) value;
    }

    private static long configuredLong(Properties properties, String suffix, long fallback) {
        String raw = properties.getProperty(PROPERTY_PREFIX + suffix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0L) {
                throw invalidProperty(suffix, raw, null);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw invalidProperty(suffix, raw, failure);
        }
    }

    private static IllegalArgumentException invalidProperty(String suffix, String value, Throwable cause) {
        return new IllegalArgumentException(
                "system property " + PROPERTY_PREFIX + suffix + " must be a positive integer: " + value,
                cause);
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
