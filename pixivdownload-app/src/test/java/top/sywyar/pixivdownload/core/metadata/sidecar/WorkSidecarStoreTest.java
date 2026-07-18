package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkSidecarStore 原子写入与命名")
class WorkSidecarStoreTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkSidecarStore store = new WorkSidecarStore(mapper);

    @Test
    @DisplayName("原子写出完整文档并使用既有 {workId}.meta.json 命名")
    void shouldWriteCompleteDocumentToStablePath() throws Exception {
        ObjectNode document = mapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("workType", "ARTWORK");
        document.put("workId", 7L);
        document.putObject("normalized").put("uploadTime", 123L);
        document.putObject("raw").put("alt", "x");

        store.write(dir, 7L, document);

        Path target = dir.resolve("7.meta.json");
        JsonNode written = mapper.readTree(target.toFile());
        assertThat(store.sidecarPath(dir, 7L)).isEqualTo(target);
        assertThat(WorkSidecarFiles.fileName(7L)).isEqualTo("7.meta.json");
        assertThat(written.toString()).isEqualTo(document.toString());
        assertThat(Files.exists(dir.resolve("7.meta.json.tmp"))).isFalse();
    }

    @Test
    @DisplayName("重复写入覆盖旧文档且不遗留临时文件")
    void shouldReplaceExistingDocument() throws Exception {
        ObjectNode first = mapper.createObjectNode().put("schemaVersion", 1).put("workId", 7L);
        ObjectNode second = mapper.createObjectNode().put("schemaVersion", 1).put("workId", 7L)
                .put("source", "schedule");

        store.write(dir, 7L, first);
        store.write(dir, 7L, second);

        assertThat(mapper.readTree(dir.resolve("7.meta.json").toFile()).toString())
                .isEqualTo(second.toString());
        assertThat(Files.exists(dir.resolve("7.meta.json.tmp"))).isFalse();
    }
}
