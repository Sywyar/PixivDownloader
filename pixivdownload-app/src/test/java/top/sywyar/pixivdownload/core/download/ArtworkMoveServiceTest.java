package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArtworkMoveServiceTest {
    @Test
    void returnsTheDatabaseResultForTheNormalizedMove() {
        var database = mock(PixivDatabase.class);
        var service = new ArtworkMoveService(database);
        when(database.updateArtworkMove(42L, "/new/path", 1700000000000L, "/dst"))
                .thenReturn(false, true);
        assertThat(service.recordMove(42L, "/new/path", 1700000000L, "/dst")).isFalse();
        assertThat(service.recordMove(42L, "/new/path", 1700000000L, "/dst")).isTrue();
    }
}
