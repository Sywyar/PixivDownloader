package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelSettings;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledNovelWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkTranslateStatus;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduledNovelDownloadDelegate} 测试：钉死「中性载体 + 设置 → {@code NovelDownloadRequest}」的逐字段
 * 映射保真（清偿调度壳把组装请求移入小说插件后可能的字段漂移），以及系列合订 / 翻译状态映射。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("计划任务小说下载委托")
class ScheduledNovelDownloadDelegateTest {

    @Mock
    private NovelDownloader novelDownloader;
    @Mock
    private NovelMergeService novelMergeService;
    @Mock
    private NovelAutoTranslateService novelAutoTranslateService;

    private ScheduledNovelDownloadDelegate delegate() {
        return new ScheduledNovelDownloadDelegate(novelDownloader, novelMergeService, novelAutoTranslateService);
    }

    @Test
    @DisplayName("中性载体 + 设置逐字段映射为 NovelDownloadRequest 并经 downloadBlocking 同步下载")
    void mapsWorkAndSettingsToRequest() {
        List<TagDto> tags = List.of(new TagDto("orig", "trans"));
        List<TagDto> seriesTags = List.of(new TagDto("s-orig", "s-trans"));
        ScheduledNovelWork work = new ScheduledNovelWork(
                111L, "标题", "正文 markup",
                9L, "作者", 1, true,
                false, "ja",
                1234, 5678, 90, 3,
                "简介", tags,
                42L, 7L, "系列名",
                1_700_000_000_000L, "https://cover", Map.of("img1", "https://px/img1"),
                "系列简介", "https://series-cover", seriesTags);
        ScheduledNovelSettings settings = new ScheduledNovelSettings(
                "{title}", true, 88L, "epub",
                true, "english", 35, true, "txt");
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(true);

        boolean ok = delegate().download(work, settings, "PHPSESSID=x");

        assertThat(ok).isTrue();
        ArgumentCaptor<NovelDownloadRequest> captor = ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(novelDownloader).downloadBlocking(captor.capture(), isNull());
        NovelDownloadRequest req = captor.getValue();
        assertThat(req.getNovelId()).isEqualTo(111L);
        assertThat(req.getTitle()).isEqualTo("标题");
        assertThat(req.getCookie()).isEqualTo("PHPSESSID=x");
        assertThat(req.getContent()).isEqualTo("正文 markup");
        NovelDownloadRequest.Other o = req.getOther();
        assertThat(o.getAuthorId()).isEqualTo(9L);
        assertThat(o.getAuthorName()).isEqualTo("作者");
        assertThat(o.getXRestrict()).isEqualTo(1);
        assertThat(o.isAi()).isTrue();
        assertThat(o.isOriginal()).isFalse();
        assertThat(o.getLanguage()).isEqualTo("ja");
        assertThat(o.getWordCount()).isEqualTo(1234);
        assertThat(o.getTextLength()).isEqualTo(5678);
        assertThat(o.getReadingTimeSeconds()).isEqualTo(90);
        assertThat(o.getPageCount()).isEqualTo(3);
        assertThat(o.getDescription()).isEqualTo("简介");
        assertThat(o.getTags()).isEqualTo(tags);
        assertThat(o.getSeriesId()).isEqualTo(42L);
        assertThat(o.getSeriesOrder()).isEqualTo(7L);
        assertThat(o.getSeriesTitle()).isEqualTo("系列名");
        assertThat(o.getUploadTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(o.getCoverUrl()).isEqualTo("https://cover");
        assertThat(o.getEmbeddedImages()).containsEntry("img1", "https://px/img1");
        assertThat(o.getFileNameTemplate()).isEqualTo("{title}");
        assertThat(o.isBookmark()).isTrue();
        assertThat(o.getCollectionId()).isEqualTo(88L);
        assertThat(o.getFormat()).isEqualTo("epub");
        // autoTranslate 开 → 四个译文字段被应用
        assertThat(o.isAutoTranslate()).isTrue();
        assertThat(o.getAutoTranslateLanguage()).isEqualTo("english");
        assertThat(o.getAutoTranslateSegmentSize()).isEqualTo(35);
        assertThat(o.isAutoTranslateMerge()).isTrue();
        assertThat(o.getAutoTranslateMergeFormat()).isEqualTo("txt");
        // 系列富信息（非空）逐字应用
        assertThat(o.getSeriesDescription()).isEqualTo("系列简介");
        assertThat(o.getSeriesCoverUrl()).isEqualTo("https://series-cover");
        assertThat(o.getSeriesTags()).isEqualTo(seriesTags);
    }

    @Test
    @DisplayName("autoTranslate 关闭时不应用译文字段；系列富信息为 null 时不设置（保持 Other 默认）")
    void autoTranslateOffAndNullSeriesNotApplied() {
        ScheduledNovelWork work = new ScheduledNovelWork(
                1L, "t", "c", null, null, 0, false, false, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null);
        // autoTranslate=false，但仍给后四字段非空值——下载侧应忽略
        ScheduledNovelSettings settings = new ScheduledNovelSettings(
                null, false, null, "txt", false, "english", 35, true, "epub");
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(false);

        boolean ok = delegate().download(work, settings, null);

        assertThat(ok).isFalse();
        ArgumentCaptor<NovelDownloadRequest> captor = ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(novelDownloader).downloadBlocking(captor.capture(), isNull());
        NovelDownloadRequest.Other o = captor.getValue().getOther();
        assertThat(o.isAutoTranslate()).isFalse();
        assertThat(o.getAutoTranslateLanguage()).isNull();
        assertThat(o.getAutoTranslateSegmentSize()).isNull();
        assertThat(o.isAutoTranslateMerge()).isFalse();
        assertThat(o.getAutoTranslateMergeFormat()).isNull();
        assertThat(o.getSeriesDescription()).isNull();
        assertThat(o.getSeriesCoverUrl()).isNull();
        assertThat(o.getSeriesTags()).isNull();
    }

    @Test
    @DisplayName("mergeSeries 解析格式并触发系列合订")
    void mergeSeriesParsesFormat() throws Exception {
        delegate().mergeSeries(42L, "epub");
        verify(novelMergeService).merge(42L, NovelDownloadService.NovelFormat.parse("epub"));
    }

    @Test
    @DisplayName("translateStatus：有状态时映射为中性记录，无状态时返回 null")
    void translateStatusMapping() {
        when(novelAutoTranslateService.getStatus(5L)).thenReturn(
                new NovelAutoTranslateService.StatusView("TRANSLATING", 12L, 3, "en", false, false, null));
        ScheduledWorkTranslateStatus tv = delegate().translateStatus(5L);
        assertThat(tv).isNotNull();
        assertThat(tv.phase()).isEqualTo("TRANSLATING");
        assertThat(tv.elapsedSeconds()).isEqualTo(12L);
        assertThat(tv.seriesPending()).isEqualTo(3);

        when(novelAutoTranslateService.getStatus(6L)).thenReturn(null);
        assertThat(delegate().translateStatus(6L)).isNull();
    }
}
