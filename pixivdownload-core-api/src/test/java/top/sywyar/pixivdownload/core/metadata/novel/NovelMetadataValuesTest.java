package top.sywyar.pixivdownload.core.metadata.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NovelMetadataValuesTest {

    @Test
    @DisplayName("兼容构造器应默认生成未删除且无上传时间的小说记录")
    void compatibilityConstructorDefaultsDeletionAndUploadTime() {
        NovelRecord record = new NovelRecord(
                42L, "小说", "C:/novels/42", 1, "txt", 1_700_000_000_000L,
                0, false, 7L, "简介", 8L, 9L, 10L, 11L,
                12, 13, 14, 15, true, "ja", "正文", "jpg");

        assertThat(record.deleted()).isFalse();
        assertThat(record.uploadTime()).isNull();
    }

    @Test
    @DisplayName("完整构造器应保留删除状态与上传时间")
    void canonicalConstructorPreservesDeletionAndUploadTime() {
        NovelRecord record = new NovelRecord(
                42L, "小说", "C:/novels/42", 1, "txt", 1_700_000_000_000L,
                0, false, 7L, "简介", 8L, 9L, 10L, 11L,
                12, 13, 14, 15, true, "ja", "正文", "jpg",
                true, 1_700_000_001_000L);

        assertThat(record.deleted()).isTrue();
        assertThat(record.uploadTime()).isEqualTo(1_700_000_001_000L);
    }

    @Test
    @DisplayName("小说系列纯值模型应原样保留全部字段")
    void novelSeriesPreservesValues() {
        NovelSeries series = new NovelSeries(
                100L, "系列", 7L, 1_700_000_002_000L,
                "系列简介", "png", "C:/novels/series-100");

        assertThat(series)
                .extracting(
                        NovelSeries::seriesId,
                        NovelSeries::title,
                        NovelSeries::authorId,
                        NovelSeries::updatedTime,
                        NovelSeries::description,
                        NovelSeries::coverExt,
                        NovelSeries::coverFolder)
                .containsExactly(
                        100L, "系列", 7L, 1_700_000_002_000L,
                        "系列简介", "png", "C:/novels/series-100");
    }
}
