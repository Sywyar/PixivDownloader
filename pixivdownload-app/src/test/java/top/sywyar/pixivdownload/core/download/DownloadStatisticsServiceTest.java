package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics.DailyOutcomes;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.StatisticsData;
import top.sywyar.pixivdownload.core.download.response.StatisticsResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        downloadStatisticsService = new DownloadStatisticsService(
                pixivDatabase,
                APP_MESSAGES,
                Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC));
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
            verify(pixivDatabase).recordCompletedDownload(5, "2026-08-24");
        }

        @Test
        @DisplayName("数据库异常时不向上抛出")
        void shouldNotThrowOnDatabaseError() {
            doThrow(new RuntimeException("DB error"))
                    .when(pixivDatabase).recordCompletedDownload(anyInt(), anyString());

            assertThatCode(() -> downloadStatisticsService.recordStatistics(5))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("失败任务应按当前本地日期记录")
        void shouldRecordFailureForCurrentDate() {
            downloadStatisticsService.recordFailure();

            verify(pixivDatabase).recordFailedDownload("2026-08-24");
        }

        @Test
        @DisplayName("只返回当前本地日期的终态计数")
        void shouldReturnOnlyCurrentDateOutcomes() {
            when(pixivDatabase.getStatisticsData()).thenReturn(
                    new StatisticsData(10, 30, 2, "2026-08-24", 3, 1),
                    new StatisticsData(10, 30, 2, "2026-08-23", 7, 2));

            assertThat(downloadStatisticsService.today()).isEqualTo(new DailyOutcomes(3, 1));
            assertThat(downloadStatisticsService.today()).isEqualTo(new DailyOutcomes(0, 0));
        }
    }
}
