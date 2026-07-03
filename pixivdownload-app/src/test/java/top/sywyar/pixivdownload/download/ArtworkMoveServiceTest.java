package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtworkMoveService 单元测试")
class ArtworkMoveServiceTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @Mock
    private PixivDatabase pixivDatabase;

    private ArtworkMoveService artworkMoveService;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        artworkMoveService = new ArtworkMoveService(pixivDatabase, APP_MESSAGES);
    }

    private static ArtworkRecord artwork(boolean moved) {
        return new ArtworkRecord(12345L, "t", "/p", 1, "jpg", 100L, moved, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("首次移动应落库并递增已移动计数（秒级时间戳转毫秒）")
    void shouldRecordMoveAndIncrementMovedOnFirstMove() {
        when(pixivDatabase.getArtwork(12345L)).thenReturn(artwork(false));

        artworkMoveService.moveArtWork(12345L, "/new/path", 1700000000L, "/dst");

        verify(pixivDatabase).updateArtworkMove(12345L, "/new/path", 1700000000000L, "/dst");
        verify(pixivDatabase).incrementMoved();
    }

    @Test
    @DisplayName("已移动过的作品再次移动应更新落点但不重复递增计数")
    void shouldNotIncrementMovedWhenAlreadyMoved() {
        when(pixivDatabase.getArtwork(12345L)).thenReturn(artwork(true));

        artworkMoveService.moveArtWork(12345L, "/new/path", 1700000000L, null);

        verify(pixivDatabase).updateArtworkMove(12345L, "/new/path", 1700000000000L, null);
        verify(pixivDatabase, never()).incrementMoved();
    }

    @Test
    @DisplayName("作品不存在时不写任何移动记录")
    void shouldDoNothingWhenArtworkMissing() {
        when(pixivDatabase.getArtwork(99999L)).thenReturn(null);

        artworkMoveService.moveArtWork(99999L, "/new/path", 1700000000L, null);

        verify(pixivDatabase, never()).updateArtworkMove(anyLong(), any(), anyLong(), any());
        verify(pixivDatabase, never()).incrementMoved();
    }

    @Test
    @DisplayName("数据库异常时不向上抛出")
    void shouldNotThrowOnDatabaseError() {
        when(pixivDatabase.getArtwork(12345L)).thenReturn(artwork(false));
        doThrow(new RuntimeException("DB error")).when(pixivDatabase)
                .updateArtworkMove(anyLong(), any(), anyLong(), any());

        assertThatCode(() -> artworkMoveService.moveArtWork(12345L, "/new/path", 1700000000L, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("三参重载应以 null 分类目标委托四参实现")
    void threeArgOverloadDelegatesWithNullClassifierTarget() {
        when(pixivDatabase.getArtwork(12345L)).thenReturn(artwork(false));

        artworkMoveService.moveArtWork(12345L, "/new/path", 1700000000L);

        verify(pixivDatabase).updateArtworkMove(12345L, "/new/path", 1700000000000L, null);
        verify(pixivDatabase).incrementMoved();
    }
}
