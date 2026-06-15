package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadStatisticsService 单元测试")
class DownloadStatisticsServiceTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @Mock
    private PixivDatabase pixivDatabase;

    private DownloadStatisticsService downloadStatisticsService;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        downloadStatisticsService = new DownloadStatisticsService(pixivDatabase, APP_MESSAGES);
    }

    @Nested
    @DisplayName("getStatistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("正常获取统计数据")
        void shouldReturnStatistics() {
            when(pixivDatabase.getStats()).thenReturn(new int[]{100, 500, 30});

            StatisticsResponse response = downloadStatisticsService.getStatistics();

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getTotalArtworks()).isEqualTo(100);
            assertThat(response.getTotalImages()).isEqualTo(500);
            assertThat(response.getTotalMoved()).isEqualTo(30);
        }

        @Test
        @DisplayName("数据库异常时应向上传播")
        void shouldPropagateOnDatabaseError() {
            when(pixivDatabase.getStats()).thenThrow(new RuntimeException("DB error"));

            assertThatCode(() -> downloadStatisticsService.getStatistics())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB error");
        }
    }

    @Nested
    @DisplayName("recordStatistics")
    class RecordStatisticsTests {

        @Test
        @DisplayName("正常记录统计不抛异常")
        void shouldRecordStatisticsSuccessfully() {
            downloadStatisticsService.recordStatistics(5);
            verify(pixivDatabase).incrementStats(5);
        }

        @Test
        @DisplayName("数据库异常时不向上抛出")
        void shouldNotThrowOnDatabaseError() {
            doThrow(new RuntimeException("DB error")).when(pixivDatabase).incrementStats(anyInt());

            assertThatCode(() -> downloadStatisticsService.recordStatistics(5))
                    .doesNotThrowAnyException();
        }
    }
}
