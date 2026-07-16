package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSidecarMeta;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkSidecarStore 读写与命名")
class WorkSidecarStoreTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkSidecarStore store = new WorkSidecarStore(mapper);

    private ObjectNode sampleDoc() {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("schemaVersion", 1);
        doc.put("workType", "ARTWORK");
        doc.put("workId", 7L);
        doc.put("fetchedAt", "2026-06-06T21:27:00Z");
        doc.put("source", "schedule");
        ObjectNode n = doc.putObject("normalized");
        n.put("uploadTime", 123L);
        n.put("isOriginal", true);
        ArrayNode pages = n.putArray("pages");
        ObjectNode p = pages.addObject();
        p.put("width", 100);
        p.put("height", 200);
        p.put("original", "https://i.pximg.net/p0.jpg");
        ObjectNode raw = doc.putObject("raw");
        raw.put("restrict", 0);
        raw.put("alt", "x");
        return doc;
    }

    @Test
    @DisplayName("原子写出后可解析回 WorkSidecarMeta（normalized typed + raw 通用 Map）")
    void shouldRoundTrip() throws Exception {
        store.write(dir, 7L, sampleDoc());
        assertThat(Files.exists(dir.resolve("7.meta.json"))).isTrue();

        Optional<WorkSidecarMeta> read = store.read(dir, WorkType.ARTWORK, 7L);
        assertThat(read).isPresent();
        WorkSidecarMeta m = read.get();
        assertThat(m.schemaVersion()).isEqualTo(1);
        assertThat(m.workType()).isEqualTo(WorkType.ARTWORK);
        assertThat(m.workId()).isEqualTo(7L);
        assertThat(m.source()).isEqualTo("schedule");
        assertThat(m.normalized().uploadTime()).isEqualTo(123L);
        assertThat(m.normalized().isOriginal()).isTrue();
        assertThat(m.normalized().pages()).hasSize(1);
        assertThat(m.normalized().pages().get(0).width()).isEqualTo(100);
        assertThat(m.normalized().pages().get(0).original()).contains("p0.jpg");
        assertThat(m.raw()).containsEntry("alt", "x").containsKey("restrict");
    }

    @Test
    @DisplayName("raw Map 防御性不可变")
    void shouldExposeImmutableRaw() throws Exception {
        store.write(dir, 7L, sampleDoc());
        WorkSidecarMeta m = store.read(dir, WorkType.ARTWORK, 7L).orElseThrow();
        assertThatThrownBy(() -> m.raw().put("y", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("文件不存在时返回空（历史作品无 sidecar 属正常）")
    void shouldReturnEmptyWhenMissing() {
        assertThat(store.read(dir, WorkType.ARTWORK, 999L)).isEmpty();
    }

    /** 把任意原始 JSON 文本直接写到 {workId}.meta.json，用于构造非法 / 损坏 sidecar。 */
    private void writeRaw(long workId, String json) throws Exception {
        Files.writeString(dir.resolve(WorkSidecarFiles.fileName(workId)), json);
    }

    @Test
    @DisplayName("损坏 JSON 返回空、不上抛")
    void shouldReturnEmptyWhenCorruptJson() throws Exception {
        writeRaw(7L, "{not valid json");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("schemaVersion != 1 判非法、返回空")
    void shouldRejectWrongSchemaVersion() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":2,\"workType\":\"ARTWORK\",\"workId\":7,\"source\":\"schedule\","
                + "\"normalized\":{},\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("workType 与请求不一致判非法、返回空")
    void shouldRejectWorkTypeMismatch() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"NOVEL\",\"workId\":7,\"source\":\"schedule\","
                + "\"normalized\":{},\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("workId 与请求 id 不一致判非法、返回空")
    void shouldRejectWorkIdMismatch() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":8,\"source\":\"schedule\","
                + "\"normalized\":{},\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("source 非法（不在 forward/schedule/backfill）判非法、返回空")
    void shouldRejectIllegalSource() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":7,\"source\":\"hacker\","
                + "\"normalized\":{},\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
        // source 缺失同样判非法
        writeRaw(8L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":8,\"normalized\":{},\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 8L)).isEmpty();
    }

    @Test
    @DisplayName("raw 非对象（如字符串）判非法、返回空")
    void shouldRejectNonObjectRaw() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":7,\"source\":\"schedule\","
                + "\"normalized\":{},\"raw\":\"oops\"}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("normalized 非对象（如数组）判非法、返回空")
    void shouldRejectNonObjectNormalized() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":7,\"source\":\"schedule\","
                + "\"normalized\":[1,2],\"raw\":{}}");
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isEmpty();
    }

    @Test
    @DisplayName("normalized / raw 缺失时按空块兼容：合法、解析为空视图")
    void shouldTolerateAbsentNormalizedAndRaw() throws Exception {
        writeRaw(7L, "{\"schemaVersion\":1,\"workType\":\"ARTWORK\",\"workId\":7,\"source\":\"backfill\"}");

        Optional<WorkSidecarMeta> read = store.read(dir, WorkType.ARTWORK, 7L);

        assertThat(read).isPresent();
        WorkSidecarMeta m = read.get();
        assertThat(m.source()).isEqualTo("backfill");
        assertThat(m.normalized().uploadTime()).isNull();
        assertThat(m.normalized().pages()).isEmpty();
        assertThat(m.raw()).isEmpty();
    }

    @Test
    @DisplayName("sidecar 落盘路径兼容既有 {workId}.meta.json 命名")
    void shouldWriteToLegacySidecarFileName() throws Exception {
        store.write(dir, 7L, sampleDoc());
        // 命名规则已下沉到 WorkSidecarFiles；此处仅验证 store 的读写路径与既有 {workId}.meta.json 命名兼容。
        assertThat(WorkSidecarFiles.fileName(7L)).isEqualTo("7.meta.json");
        assertThat(Files.exists(dir.resolve("7.meta.json"))).isTrue();
        assertThat(store.read(dir, WorkType.ARTWORK, 7L)).isPresent();
    }
}
