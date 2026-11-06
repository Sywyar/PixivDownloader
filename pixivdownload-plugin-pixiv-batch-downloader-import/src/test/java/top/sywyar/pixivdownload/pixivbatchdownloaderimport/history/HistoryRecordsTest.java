package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("历史记录解析和有界暂存")
class HistoryRecordsTest {
    @TempDir Path temporary;
    private Path json(String value) throws Exception {
        return Files.writeString(temporary.resolve("history.json"), value, StandardCharsets.UTF_8);
    }
    private List<HistoryRecords.Work> read(String value) throws Exception {
        try (var parsed = HistoryRecords.read(json(value), temporary, temporary.resolve("cache"))) {
            var result = new ArrayList<HistoryRecords.Work>();
            parsed.forEach(result::add);
            return result;
        }
    }
    @Test @DisplayName("无序多页合并，完全重复去重，小说和插画身份分开")
    void groupsUnorderedRecords() throws Exception {
        Files.writeString(temporary.resolve("a.png"), "a");
        Files.writeString(temporary.resolve("b.png"), "b");
        Files.writeString(temporary.resolve("novel.txt"), "正文");
        var works = read("""
            [{"id":"42_p1","n":"b.png","d":"2020/01/02"},
             {"id":"42","n":"novel.txt"},{"id":"42_p0","n":"a.png"},{"id":"42_p1","n":"b.png"}]
            """);
        assertEquals(2, works.size());
        var art = works.stream().filter(w -> !w.bare).findFirst().orElseThrow();
        assertEquals(List.of(Path.of("a.png"), Path.of("b.png")), List.copyOf(art.files.values()));
        assertFalse(art.conflict || art.missing);
        try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
    }
    @Test @DisplayName("缺页、空文件名、缺文件、重复页冲突及目录越界不能成为候选")
    void rejectsIncompleteAndUnsafeFiles() throws Exception {
        Files.writeString(temporary.resolve("a"), "a");
        Files.writeString(temporary.resolve("b"), "b");
        var works = read("""
            [{"id":"1_p1","n":"a"},{"id":"2_p0","n":""},{"id":"3_p0","n":"absent"},
             {"id":"4_p0","n":"a"},{"id":"4_p0","n":"b"},{"id":"5_p0","n":"../outside"},
             {"id":"6_p0","n":"a"},{"id":"6_p1","n":"a"}]
            """);
        assertEquals(6, works.size());
        assertTrue(works.stream().allMatch(w -> w.missing || w.conflict));
        assertEquals(3, works.stream().filter(w -> w.conflict).count());
    }
    @Test @DisplayName("结构、重复键、尾随数据和过长字段失败后清理全部暂存")
    void invalidJsonLeavesNoPlan() throws Exception {
        for (String input : List.of("null", "{}", "[{}", "[] []",
                "[{\"id\":\"1_p0\",\"id\":\"2_p0\",\"n\":\"a\"}]",
                "[{\"id\":\"1_p0\",\"n\":\"a\",\"extra\":[]}]",
                "[{\"id\":\"1_p0\",\"n\":\"" + "x".repeat(5000) + "\"}]")) {
            assertThrows(java.io.IOException.class, () -> read(input));
            try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
        }
    }
    @Test @DisplayName("导出超过字节上限时不创建暂存；预览中断时清理暂存")
    void byteBudgetAndCancellation() throws Exception {
        Path file = json("[]");
        try (var large = new java.io.RandomAccessFile(file.toFile(), "rw")) { large.setLength(HistoryRecords.MAX_BYTES + 1); }
        assertThrows(HistoryRecords.Invalid.class, () -> HistoryRecords.read(file, temporary, temporary.resolve("cache")));
        assertFalse(Files.exists(temporary.resolve("cache")));
        json("[{\"id\":\"1_p0\",\"n\":\"a\"}]");
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> HistoryRecords.read(file, temporary, temporary.resolve("cache"))); }
        finally { Thread.interrupted(); }
        try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
    }
    @Test @DisplayName("分桶候选回读保持完整页序，预览不修改源文件")
    void candidateRoundTrip() throws Exception {
        Path source = Files.writeString(temporary.resolve("source.png"), "image");
        byte[] before = Files.readAllBytes(source);
        try (var parsed = HistoryRecords.read(json("[{\"id\":\"7_p0\",\"n\":\"source.png\"}]"), temporary, temporary.resolve("cache"))) {
            try (var output = parsed.candidateOutput()) { parsed.forEach(work -> parsed.writeCandidate(output, work)); }
            try (var input = HistoryRecords.input(parsed.candidates())) {
                var work = parsed.readCandidate(input);
                assertEquals(7, work.key.id());
                assertEquals(Path.of("source.png"), work.files.get(0));
                assertNull(parsed.readCandidate(input));
            }
        }
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test @DisplayName("同目录分片联合检查，跨文件页面可组成完整作品")
    void combinesShards() throws Exception {
        Files.writeString(temporary.resolve("a.png"), "a");
        Files.writeString(temporary.resolve("b.png"), "b");
        Path shards = Files.createDirectory(temporary.resolve("shards"));
        Files.writeString(shards.resolve("a.json"), "[{\"id\":\"42_p0\",\"n\":\"a.png\"}]");
        Files.writeString(shards.resolve("b.json"), "[{\"id\":\"42_p1\",\"n\":\"b.png\"}]");
        try (var parsed = HistoryRecords.read(shards, temporary, temporary.resolve("cache"))) {
            assertEquals(2, parsed.records());
            var works = new ArrayList<HistoryRecords.Work>();
            parsed.forEach(works::add);
            assertEquals(1, works.size());
            assertFalse(works.get(0).missing || works.get(0).conflict);
        }
    }

    @Test @DisplayName("读取上游桌面分片规模的真实文件，全部记录计数且暂存释放")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "history.scale", matches = "true")
    void upstreamSizedFile() throws Exception {
        Path file = temporary.resolve("large.json");
        long started = System.nanoTime();
        int count = 0;
        long bytes = 1;
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("[");
            while (bytes < 300L * 1024 * 1024) {
                String row = (count == 0 ? "" : ",") + "{\"id\":\"" + (10_000_000 + count)
                        + "_p0\",\"n\":\"missing/" + "a".repeat(240) + "/" + count + ".png\",\"d\":\"2026/01/01\"}";
                writer.write(row); bytes += row.length(); count++;
            }
            writer.write("]");
        }
        int[] seen = {0};
        try (var parsed = HistoryRecords.read(file, temporary, temporary.resolve("cache"))) {
            assertEquals(count, parsed.records());
            parsed.forEach(work -> { assertTrue(work.missing); seen[0]++; });
        }
        assertEquals(count, seen[0]);
        try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
        System.out.printf(java.util.Locale.ROOT, "history-scale bytes=%d records=%d seconds=%.2f maxHeap=%d%n",
                Files.size(file), count, (System.nanoTime() - started) / 1e9, Runtime.getRuntime().maxMemory());
    }
}
