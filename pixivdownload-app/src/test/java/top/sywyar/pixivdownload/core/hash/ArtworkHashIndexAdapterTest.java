package top.sywyar.pixivdownload.core.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ArtworkHashIndexAdapter 核心哈希端口测试")
class ArtworkHashIndexAdapterTest {

    private final ImageHashMapper mapper = mock(ImageHashMapper.class);
    private final PixivDatabase database = mock(PixivDatabase.class);
    private final ArtworkHashService hashService = mock(ArtworkHashService.class);
    private final ArtworkHashIndexAdapter adapter = new ArtworkHashIndexAdapter(mapper, database, hashService);

    @Test
    @DisplayName("查询端口只投影相似检测需要的核心哈希字段")
    void projectsHashEntriesAndFingerprint() {
        when(mapper.findAll()).thenReturn(List.of(new ImageHashRow(
                42L, 3, "jpg", 7L, 9L, 123L,
                "title", 88L, "author", 1)));
        when(mapper.countAllHashRows()).thenReturn(5L);
        when(mapper.maxCreatedTime()).thenReturn(123L);

        assertThat(adapter.findAllEntries()).containsExactly(new ArtworkHashEntry(
                42L, 3, 7L, 9L, "title", 88L, "author", 1));
        assertThat(adapter.fingerprint()).isEqualTo(new ArtworkHashFingerprint(5L, 123L));
    }

    @Test
    @DisplayName("重建端口区分作品不存在与成功写入")
    void rebuildDistinguishesMissingArtworkFromWrittenHashes() {
        ArtworkRecord artwork = mock(ArtworkRecord.class);
        when(database.getArtwork(1L)).thenReturn(null);
        when(database.getArtwork(2L)).thenReturn(artwork);
        when(hashService.recordArtworkHashes(artwork)).thenReturn(4);

        assertThat(adapter.rebuildArtwork(1L)).isEmpty();
        assertThat(adapter.rebuildArtwork(2L)).hasValue(4);
        verify(hashService, never()).recordArtworkHashes(null);
        verify(hashService).recordArtworkHashes(artwork);
    }
}
