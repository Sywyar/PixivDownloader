package top.sywyar.pixivdownload.core.archive;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 一个待写入归档的文件或内存字节条目。
 */
public record ArchiveExportEntry(Path sourcePath, String entryName, byte[] bytes, Long workId) {

    /**
     * 创建 {@code ArchiveExportEntry} 实例。
     *
     * @param sourcePath 来源路径
     * @param entryName 条目名称
     * @param bytes 字节数
     * @param workId 作品标识
     */
    public ArchiveExportEntry {
        bytes = copy(bytes);
    }

    /**
     * 返回字节数。
     *
     * @return 返回的字节数据
     */
    @Override
    public byte[] bytes() {
        return copy(bytes);
    }

    /**
     * 执行文件并返回结果。
     *
     * @param sourcePath 来源路径
     * @param entryName 条目名称
     * @return 方法返回的 {@code ArchiveExportEntry} 实例
     */
    public static ArchiveExportEntry file(Path sourcePath, String entryName) {
        return file(sourcePath, entryName, null);
    }

    /**
     * 执行文件并返回结果。
     *
     * @param sourcePath 来源路径
     * @param entryName 条目名称
     * @param workId 作品标识
     * @return 方法返回的 {@code ArchiveExportEntry} 实例
     */
    public static ArchiveExportEntry file(Path sourcePath, String entryName, Long workId) {
        return new ArchiveExportEntry(sourcePath, entryName, null, workId);
    }

    /**
     * 执行字节数并返回结果。
     *
     * @param entryName 条目名称
     * @param bytes 字节数
     * @return 方法返回的 {@code ArchiveExportEntry} 实例
     */
    public static ArchiveExportEntry bytes(String entryName, byte[] bytes) {
        return new ArchiveExportEntry(null, entryName, bytes, null);
    }

    private static byte[] copy(byte[] source) {
        return source == null ? null : Arrays.copyOf(source, source.length);
    }
}
