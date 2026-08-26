package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadStatistics.DailyOutcomes;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.work.model.WorkTag;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("插画下载核心端口适配器")
class ArtworkDownloadPortsAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("历史适配器应分配时间并完整映射下载事实")
    void historyAdapterAllocatesTimeAndMapsFacts() {
        PixivDatabase pixivDatabase = mock(PixivDatabase.class);
        ArtworkDownloadHistoryAdapter adapter = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        when(pixivDatabase.getUniqueTime()).thenReturn(101L);
        when(pixivDatabase.getUniqueTime(202L)).thenReturn(203L);
        when(pixivDatabase.getOrCreateFileNameTemplateId("{artwork_title}_p{page}")).thenReturn(5L);
        when(pixivDatabase.getOrCreateFileAuthorNameId("author")).thenReturn(6L);

        assertThat(adapter.allocateRecordTime(0L)).isEqualTo(101L);
        assertThat(adapter.allocateRecordTime(202L)).isEqualTo(203L);

        ArtworkDownloadCompletion completion = sampleCompletion();

        adapter.record(completion);

        verify(pixivDatabase).getOrCreateFileNameTemplateId("{artwork_title}_p{page}");
        verify(pixivDatabase).getOrCreateFileAuthorNameId("author");
        verify(pixivDatabase).insertArtwork(InsertArtworkArgument.builder()
                .artworkId(42L)
                .title("title")
                .folder(tempDir.resolve("42").toAbsolutePath().toString())
                .count(2)
                .extensions("png,jpg")
                .time(303L)
                .xRestrict(1)
                .isAi(true)
                .authorId(84L)
                .description("description")
                .fileName(5L)
                .fileAuthorNameId(6L)
                .seriesId(7L)
                .seriesOrder(8L)
                .build());
        verify(pixivDatabase).refreshArtworkMetadataAfterDownload(
                42L, "title", 1, true, 84L, "description", 5L, 6L, 7L, 8L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TagDto>> tags = ArgumentCaptor.forClass(List.class);
        verify(pixivDatabase).replaceArtworkTagsAfterDownload(eq(42L), tags.capture());
        assertThat(tags.getValue()).singleElement().satisfies(tag -> {
            assertThat(tag.getTagId()).isEqualTo(9L);
            assertThat(tag.getName()).isEqualTo("tag");
            assertThat(tag.getTranslatedName()).isEqualTo("translated");
        });
    }

    @Test
    @DisplayName("主历史写入失败时不得继续写入标签")
    void insertFailureStopsBeforeTags() {
        PixivDatabase pixivDatabase = mock(PixivDatabase.class);
        ArtworkDownloadHistoryAdapter adapter = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        RuntimeException failure = new RuntimeException("insert failed");
        doThrow(failure).when(pixivDatabase).insertArtwork(any(InsertArtworkArgument.class));

        assertThatThrownBy(() -> adapter.record(sampleCompletion())).isSameAs(failure);

        verify(pixivDatabase, never()).replaceArtworkTagsAfterDownload(anyLong(), anyList());
    }

    @Test
    @DisplayName("标签写入失败应向调用侧报告并交由事务回滚")
    void tagFailurePropagatesAfterHistoryWrite() {
        PixivDatabase pixivDatabase = mock(PixivDatabase.class);
        ArtworkDownloadHistoryAdapter adapter = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        RuntimeException failure = new RuntimeException("tag failed");
        doThrow(failure).when(pixivDatabase).replaceArtworkTagsAfterDownload(eq(42L), anyList());

        assertThatThrownBy(() -> adapter.record(sampleCompletion())).isSameAs(failure);

        verify(pixivDatabase).insertArtwork(any(InsertArtworkArgument.class));
    }

    @Test
    @DisplayName("重下得到空值或作品 ID 占位标题时应保留旧元数据")
    void historyAdapterKeepsExistingMetadataForBadReplacement() {
        PixivDatabase pixivDatabase = mock(PixivDatabase.class);
        ArtworkDownloadHistoryAdapter adapter = new ArtworkDownloadHistoryAdapter(pixivDatabase);
        when(pixivDatabase.getArtwork(42L)).thenReturn(new ArtworkRecord(
                42L, "旧标题", "/old", 1, "jpg", 100L, false, null, null,
                2, true, 84L, "旧简介", 4L, 6L, 7L, 8L, false));
        when(pixivDatabase.getOrCreateFileNameTemplateId("{artwork_id}_p{page}")).thenReturn(5L);

        adapter.record(new ArtworkDownloadCompletion(
                42L, "作品 42", tempDir.resolve("42"), 2,
                new LinkedHashSet<>(List.of("png", "jpg")), 303L,
                0, false, null, " ", "{artwork_id}_p{page}", null,
                null, null, null));

        verify(pixivDatabase).insertArtwork(InsertArtworkArgument.builder()
                .artworkId(42L)
                .title("旧标题")
                .folder(tempDir.resolve("42").toAbsolutePath().toString())
                .count(2)
                .extensions("png,jpg")
                .time(303L)
                .xRestrict(2)
                .isAi(true)
                .authorId(84L)
                .description("旧简介")
                .fileName(5L)
                .fileAuthorNameId(6L)
                .seriesId(7L)
                .seriesOrder(8L)
                .build());
        verify(pixivDatabase).refreshArtworkMetadataAfterDownload(
                42L, null, null, null, null, null, 5L, null, null, null);
        verify(pixivDatabase).replaceArtworkTagsAfterDownload(42L, List.of());
    }

    @Test
    @DisplayName("历史主行与标签写入必须共享事务边界")
    void historyAndTagsShareTransactionBoundary() throws NoSuchMethodException {
        assertThat(ArtworkDownloadHistoryAdapter.class
                .getMethod("record", ArtworkDownloadCompletion.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    @DisplayName("判重适配器应保留磁盘校验与恢复语义")
    void lookupAdapterDelegatesFileVerification() {
        DownloadedArtworkService downloadedArtworkService = mock(DownloadedArtworkService.class);
        ArtworkDownloadLookupAdapter adapter = new ArtworkDownloadLookupAdapter(downloadedArtworkService);
        when(downloadedArtworkService.getDownloadedRecord(42L, true))
                .thenReturn(mock(ArtworkRecord.class));

        assertThat(adapter.isDownloaded(42L, true)).isTrue();
        assertThat(adapter.isDownloaded(43L, false)).isFalse();

        verify(downloadedArtworkService).getDownloadedRecord(42L, true);
        verify(downloadedArtworkService).getDownloadedRecord(43L, false);
    }

    @Test
    @DisplayName("统计适配器应原样委托下载终态")
    void statisticsAdapterDelegatesOutcomes() {
        DownloadStatisticsService downloadStatisticsService = mock(DownloadStatisticsService.class);
        ArtworkDownloadStatisticsAdapter adapter =
                new ArtworkDownloadStatisticsAdapter(downloadStatisticsService);
        DailyOutcomes today = new DailyOutcomes(3, 1);
        when(downloadStatisticsService.today()).thenReturn(today);

        adapter.recordCompleted(4);
        adapter.recordFailed();

        verify(downloadStatisticsService).recordStatistics(4);
        verify(downloadStatisticsService).recordFailure();
        assertThat(adapter.today()).isSameAs(today);
    }

    private ArtworkDownloadCompletion sampleCompletion() {
        LinkedHashSet<String> extensions = new LinkedHashSet<>(List.of("png", "jpg"));
        return new ArtworkDownloadCompletion(
                42L,
                "title",
                tempDir.resolve("42"),
                2,
                extensions,
                303L,
                1,
                true,
                84L,
                "description",
                "{artwork_title}_p{page}",
                "author",
                7L,
                8L,
                List.of(new WorkTag(9L, "tag", "translated"))
        );
    }
}
