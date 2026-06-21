package top.sywyar.pixivdownload.download.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("WorkMetaCaptureService 捕获落地")
class WorkMetaCaptureServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private PixivDatabase pixivDatabase;
    private NovelMetadataRepository novelMetadataRepository;
    private ArtworkFileLocator artworkFileLocator;
    private WorkMetaCaptureService service;

    private static final String UPLOAD_ISO = "2026-06-06T21:27:00+00:00";
    private static final long UPLOAD_MILLIS = OffsetDateTime.parse(UPLOAD_ISO).toInstant().toEpochMilli();

    @BeforeEach
    void setUp() {
        pixivDatabase = mock(PixivDatabase.class);
        novelMetadataRepository = mock(NovelMetadataRepository.class);
        artworkFileLocator = mock(ArtworkFileLocator.class);
        service = new WorkMetaCaptureService(new WorkMetaCurator(mapper), new WorkSidecarStore(mapper),
                pixivDatabase, novelMetadataRepository, artworkFileLocator, mapper);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ArtworkRecord artwork(long id) {
        return new ArtworkRecord(id, "t", tempDir.toString(), 1, "jpg", 1000L, false, null, null, 0, false, 1L, null);
    }

    private NovelRecord novel(long id) {
        return new NovelRecord(id, "n", tempDir.toString(), 1, "txt", 1000L, 0, false, null, null,
                null, null, null, null, null, null, null, null, true, null, "正文", null);
    }

    @Test
    @DisplayName("插画：写 upload_time/is_original 列投影 + 落盘 sidecar")
    void shouldCaptureArtwork() {
        when(pixivDatabase.getArtwork(7L)).thenReturn(artwork(7L));
        when(artworkFileLocator.resolveArtworkDirectory(any())).thenReturn(tempDir.toString());

        service.captureArtwork(7L, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,\"description\":\"d\"}"),
                null, "schedule");

        verify(pixivDatabase).updateArtworkUploadMeta(eq(7L), eq(UPLOAD_MILLIS), eq(true));
        assertThat(Files.exists(tempDir.resolve("7.meta.json"))).isTrue();
    }

    @Test
    @DisplayName("小说：写 upload_time 列投影 + 落盘 sidecar，且 sidecar 不含正文")
    void shouldCaptureNovel() throws Exception {
        when(novelMetadataRepository.getNovel(5L)).thenReturn(novel(5L));

        service.captureNovel(5L, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                + "\"content\":\"很长的正文……\",\"description\":\"d\"}"), "schedule");

        verify(novelMetadataRepository).updateNovelUploadTime(eq(5L), eq(UPLOAD_MILLIS));
        Path sidecar = tempDir.resolve("5.meta.json");
        assertThat(Files.exists(sidecar)).isTrue();
        assertThat(Files.readString(sidecar)).doesNotContain("很长的正文");
    }

    @Test
    @DisplayName("body 为 null 直接跳过、不触 DB / 不写盘")
    void shouldSkipNullBody() {
        service.captureArtwork(7L, null, null, "schedule");
        verifyNoInteractions(pixivDatabase, artworkFileLocator);
    }

    @Test
    @DisplayName("前端转发：解析轻剪枝 body JSON 串后归一化，写列投影 + sidecar 且来源标记 forward")
    void shouldCaptureForwardedArtwork() throws Exception {
        when(pixivDatabase.getArtwork(9L)).thenReturn(artwork(9L));
        when(artworkFileLocator.resolveArtworkDirectory(any())).thenReturn(tempDir.toString());

        service.captureForwardedArtwork(9L, "{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":false,\"description\":\"d\"}");

        verify(pixivDatabase).updateArtworkUploadMeta(eq(9L), eq(UPLOAD_MILLIS), eq(false));
        Path sidecar = tempDir.resolve("9.meta.json");
        assertThat(Files.exists(sidecar)).isTrue();
        assertThat(Files.readString(sidecar)).contains("\"source\":\"forward\"");
    }

    @Test
    @DisplayName("前端转发：空串 / 非法 JSON 直接跳过、不触 DB / 不写盘")
    void shouldSkipForwardedArtworkOnBlankOrInvalidJson() {
        service.captureForwardedArtwork(9L, "   ");
        service.captureForwardedArtwork(9L, "not json {");
        verifyNoInteractions(pixivDatabase, artworkFileLocator);
    }

    @Test
    @DisplayName("前端转发小说：解析轻剪枝 body JSON 串后归一化，写 upload_time + sidecar 且来源 forward，正文/内嵌图不入 sidecar")
    void shouldCaptureForwardedNovel() throws Exception {
        when(novelMetadataRepository.getNovel(6L)).thenReturn(novel(6L));

        service.captureForwardedNovel(6L, "{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                + "\"content\":\"很长的正文……\",\"textEmbeddedImages\":{\"1\":{\"urls\":{\"original\":\"x\"}}},"
                + "\"description\":\"d\"}");

        verify(novelMetadataRepository).updateNovelUploadTime(eq(6L), eq(UPLOAD_MILLIS));
        Path sidecar = tempDir.resolve("6.meta.json");
        assertThat(Files.exists(sidecar)).isTrue();
        String content = Files.readString(sidecar);
        assertThat(content).contains("\"source\":\"forward\"");
        assertThat(content).doesNotContain("很长的正文");
        assertThat(content).doesNotContain("textEmbeddedImages");
    }

    @Test
    @DisplayName("前端转发小说：空串 / 非法 JSON 直接跳过、不触 DB / 不写盘")
    void shouldSkipForwardedNovelOnBlankOrInvalidJson() {
        service.captureForwardedNovel(6L, "   ");
        service.captureForwardedNovel(6L, "not json {");
        verifyNoInteractions(novelMetadataRepository);
        assertThat(Files.exists(tempDir.resolve("6.meta.json"))).isFalse();
    }

    @Test
    @DisplayName("插画超总大小上限：写列投影但拒绝落盘 sidecar（不落 raw 残缺半成品）")
    void shouldNotWriteArtworkSidecarWhenOversized() {
        String big = "x".repeat(300_000);
        service.captureArtwork(7L, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                + "\"description\":\"" + big + "\"}"), null, "schedule");

        verify(pixivDatabase).updateArtworkUploadMeta(eq(7L), eq(UPLOAD_MILLIS), eq(true));
        verify(pixivDatabase, never()).getArtwork(anyLong());
        assertThat(Files.exists(tempDir.resolve("7.meta.json"))).as("被拒时不落盘").isFalse();
    }

    @Test
    @DisplayName("小说超总大小上限：写列投影但拒绝落盘 sidecar（不落 raw 残缺半成品）")
    void shouldNotWriteNovelSidecarWhenOversized() {
        String big = "x".repeat(300_000);
        service.captureNovel(5L, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true,"
                + "\"description\":\"" + big + "\"}"), "schedule");

        verify(novelMetadataRepository).updateNovelUploadTime(eq(5L), eq(UPLOAD_MILLIS));
        verify(novelMetadataRepository, never()).getNovel(anyLong());
        assertThat(Files.exists(tempDir.resolve("5.meta.json"))).as("被拒时不落盘").isFalse();
    }

    @Test
    @DisplayName("列投影写失败 warn-continue：sidecar 仍照常落盘（互不阻断）")
    void shouldStillWriteSidecarWhenColumnWriteFails() {
        when(pixivDatabase.getArtwork(7L)).thenReturn(artwork(7L));
        when(artworkFileLocator.resolveArtworkDirectory(any())).thenReturn(tempDir.toString());
        doThrow(new RuntimeException("db down")).when(pixivDatabase)
                .updateArtworkUploadMeta(anyLong(), any(), anyBoolean());

        service.captureArtwork(7L, json("{\"uploadDate\":\"" + UPLOAD_ISO + "\",\"isOriginal\":true}"),
                null, "schedule");

        assertThat(Files.exists(tempDir.resolve("7.meta.json"))).isTrue();
    }
}
