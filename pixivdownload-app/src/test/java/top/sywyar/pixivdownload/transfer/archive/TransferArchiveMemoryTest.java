package top.sywyar.pixivdownload.transfer.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@DisplayName("大于堆容量的加密归档仍可流式写入、认证和提取")
class TransferArchiveMemoryTest {
    @TempDir Path directory;

    @Test void streamsBeyondHeapSize() throws Exception {
        Path log = directory.resolve("memory.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-Dfile.encoding=UTF-8", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TransferArchiveMemoryTest.class.getName(), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("流式归档子进程应完成").isTrue();
            String result = Files.readString(log);
            assertThat(process.exitValue()).as(result).isZero();
            assertThat(result).contains("STREAM_VERIFIED bytes=134217728");
            System.out.print(result);
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    public static void main(String[] arguments) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        Path directory = Path.of(arguments[0]);
        long bytes = 128L * 1024 * 1024;
        char[] password = "fixture-memory-check".toCharArray();
        var archive = new TransferArchive();
        long start = System.nanoTime();
        var expected = java.security.MessageDigest.getInstance("SHA-256");
        var source = new TransferArchive.Source(new TransferArchive.Descriptor("host", TransferArchive.Category.DATABASE, "rows", 1),
                () -> new java.security.DigestInputStream(new InputStream() {
                    private final byte[] block = new byte[65536];
                    private final Random random = new Random(42);
                    private long remaining = bytes;
                    private int position = block.length;
                    @Override public int read() {
                        if (remaining == 0) return -1;
                        if (position == block.length) { random.nextBytes(block); position = 0; }
                        remaining--; return block[position++] & 255;
                    }
                    @Override public int read(byte[] buffer, int offset, int length) {
                        Objects.checkFromIndexSize(offset, length, buffer.length);
                        if (length == 0) return 0;
                        if (remaining == 0) return -1;
                        if (position == block.length) { random.nextBytes(block); position = 0; }
                        int count = (int) Math.min(Math.min(length, block.length - position), remaining);
                        System.arraycopy(block, position, buffer, offset, count);
                        position += count; remaining -= count; return count;
                    }
                }, expected));
        try {
            Path output = directory.resolve("stream.pdbackup");
            var preview = archive.write(output, password, "fixture", List.of(source));
            String hash = HexFormat.of().formatHex(expected.digest());
            if (preview.items().get(0).size() != bytes || !preview.items().get(0).sha256().equals(hash)) throw new AssertionError("SOURCE_DIGEST");
            archive.extract(output, password, directory.resolve("extracted"), preview);
            var restored = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(directory.resolve("extracted/0")), restored)) {
                if (input.transferTo(OutputStream.nullOutputStream()) != bytes) throw new AssertionError("SIZE");
            }
            if (!HexFormat.of().formatHex(restored.digest()).equals(hash)) throw new AssertionError("RESTORED_DIGEST");
            System.out.println("STREAM_VERIFIED bytes=" + bytes + " maxHeap=" + Runtime.getRuntime().maxMemory()
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } finally { Arrays.fill(password, '\0'); }
    }
}
