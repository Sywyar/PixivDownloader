package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("作品回填不可达记录持久化")
class ArtworksBackFillUnreachableStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("保存并重载时保留首次时间、原因和累计尝试次数")
    void preservesEntryHistoryAcrossReloads() throws Exception {
        Path path = temporaryDirectory.resolve("state").resolve("unreachable.json");
        ArtworksBackFillUnreachableStore store = ArtworksBackFillUnreachableStore.load(path, mapper);

        assertFalse(store.contains(42L));
        store.record(42L, "deleted");
        store.save();

        JsonNode first = mapper.readTree(path.toFile()).path("42");
        long firstSeenAt = first.path("firstSeenAt").asLong();
        assertEquals("deleted", first.path("reason").asText());
        assertEquals(1, first.path("attempts").asInt());
        assertTrue(firstSeenAt > 0L);

        Files.writeString(
                path,
                Files.readString(path, StandardCharsets.UTF_8)
                        .replaceFirst("\\{", "{\n  \"not-an-id\": {},"),
                StandardCharsets.UTF_8
        );
        ArtworksBackFillUnreachableStore reloaded = ArtworksBackFillUnreachableStore.load(path, mapper);
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.contains(42L));

        reloaded.record(42L, "");
        reloaded.save();

        JsonNode second = mapper.readTree(path.toFile()).path("42");
        assertEquals("deleted", second.path("reason").asText());
        assertEquals(firstSeenAt, second.path("firstSeenAt").asLong());
        assertEquals(2, second.path("attempts").asInt());
        assertTrue(second.path("lastSeenAt").asLong() >= firstSeenAt);
    }
}
