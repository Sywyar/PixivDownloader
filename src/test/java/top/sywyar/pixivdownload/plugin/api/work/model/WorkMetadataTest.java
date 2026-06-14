package top.sywyar.pixivdownload.plugin.api.work.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkMetadata 小说块不变量契约测试")
class WorkMetadataTest {

    private static final NovelWorkDetails NOVEL_DETAILS = new NovelWorkDetails(
            1000, 2000, 300, 4, true, "ja", "jpg", List.of("img-a"), List.of("zh-CN"));

    private static WorkMetadata build(WorkType workType, NovelWorkDetails novel) {
        return new WorkMetadata(workType, 1L, "标题", null, 0, false,
                null, null, null, null, null, List.of(), 100L, 1, "jpg", "/p/1",
                false, null, null, null, null, null, null, null, novel);
    }

    @Test
    @DisplayName("NOVEL 行必须携带小说块，ARTWORK 行必须为 null（type==NOVEL ⇔ novel!=null）")
    void shouldEnforceNovelBlockInvariant() {
        assertThat(build(WorkType.ARTWORK, null).novel()).isNull();
        assertThat(build(WorkType.NOVEL, NOVEL_DETAILS).novel()).isEqualTo(NOVEL_DETAILS);

        assertThatThrownBy(() -> build(WorkType.NOVEL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(WorkType.ARTWORK, NOVEL_DETAILS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("小说块的内嵌图片与译文语言列表为防御性拷贝，null 归一为空列表")
    void shouldCopyNovelDetailListsDefensively() {
        NovelWorkDetails details = new NovelWorkDetails(
                null, null, null, null, null, null, null, null, null);
        assertThat(details.embeddedImageIds()).isEmpty();
        assertThat(details.translatedLanguages()).isEmpty();

        assertThatThrownBy(() -> NOVEL_DETAILS.embeddedImageIds().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NOVEL_DETAILS.translatedLanguages().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
