package top.sywyar.pixivdownload.core.archive;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 一个待写入归档的文件或内存字节条目。
 */
public record ArchiveExportEntry(Path sourcePath, String entryName, byte[] bytes, Long workId) {

    public ArchiveExportEntry {
        bytes = copy(bytes);
    }

    @Override
    public byte[] bytes() {
        return copy(bytes);
    }

    public static ArchiveExportEntry file(Path sourcePath, String entryName) {
        return file(sourcePath, entryName, null);
    }

    public static ArchiveExportEntry file(Path sourcePath, String entryName, Long workId) {
        return new ArchiveExportEntry(sourcePath, entryName, null, workId);
    }

    public static ArchiveExportEntry bytes(String entryName, byte[] bytes) {
        return new ArchiveExportEntry(null, entryName, bytes, null);
    }

    private static byte[] copy(byte[] source) {
        return source == null ? null : Arrays.copyOf(source, source.length);
    }
}
