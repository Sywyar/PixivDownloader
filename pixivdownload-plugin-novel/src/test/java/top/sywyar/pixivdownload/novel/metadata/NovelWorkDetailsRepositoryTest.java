package top.sywyar.pixivdownload.novel.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.novel.db.NovelMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说插件自有详情批量读取")
class NovelWorkDetailsRepositoryTest {

    @Mock
    private NovelMapper novelMapper;

    @Test
    @DisplayName("基础行、内嵌图与译文语言各批量查询一次，并按输入顺序装配")
    void shouldHydrateOwnedDetailsWithThreeBatchQueries() {
        NovelWorkDetailsRepository repository = new NovelWorkDetailsRepository(novelMapper);
        List<Long> normalizedIds = List.of(2L, 1L, 404L);
        when(novelMapper.findWorkDetailsByIds(normalizedIds)).thenReturn(List.of(
                new NovelMapper.NovelWorkDetailsRow(1L, 100, 200, 30, 1, "ja", "jpg"),
                new NovelMapper.NovelWorkDetailsRow(2L, 300, 400, 60, 2, "en", null)));
        when(novelMapper.findNovelImageIdsByIds(List.of(2L, 1L))).thenReturn(List.of(
                new NovelMapper.NovelWorkDetailValueRow(1L, "img-b"),
                new NovelMapper.NovelWorkDetailValueRow(1L, "img-a")));
        when(novelMapper.findTranslationLangsByIds(List.of(2L, 1L))).thenReturn(List.of(
                new NovelMapper.NovelWorkDetailValueRow(2L, "ja"),
                new NovelMapper.NovelWorkDetailValueRow(1L, "zh-CN")));

        Map<Long, NovelWorkDetails> result = repository.findAll(
                Arrays.asList(2L, null, 1L, 2L, 0L, 404L));

        assertThat(result.keySet()).containsExactly(2L, 1L);
        assertThat(result.get(2L).wordCount()).isEqualTo(300);
        assertThat(result.get(2L).translatedLanguages()).containsExactly("ja");
        assertThat(result.get(1L).embeddedImageIds()).containsExactly("img-b", "img-a");
        assertThat(result.get(1L).translatedLanguages()).containsExactly("zh-CN");
        assertThatThrownBy(() -> result.put(3L, result.get(1L)))
                .isInstanceOf(UnsupportedOperationException.class);

        verify(novelMapper).findWorkDetailsByIds(normalizedIds);
        verify(novelMapper).findNovelImageIdsByIds(List.of(2L, 1L));
        verify(novelMapper).findTranslationLangsByIds(List.of(2L, 1L));
        verifyNoMoreInteractions(novelMapper);
    }

    @Test
    @DisplayName("空输入不访问数据库，详情中的 null 列表归一为不可变空列表")
    void shouldReturnImmutableValuesWithoutVacuousQueries() {
        NovelWorkDetailsRepository repository = new NovelWorkDetailsRepository(novelMapper);

        assertThat(repository.findAll(List.of())).isEmpty();
        assertThat(repository.findAll(null)).isEmpty();
        assertThat(repository.findWordCounts(List.of())).isEmpty();
        assertThat(repository.findWordCounts(null)).isEmpty();
        verifyNoInteractions(novelMapper);

        NovelWorkDetails details = new NovelWorkDetails(
                1L, null, null, null, null, null, null, null, null);
        assertThat(details.embeddedImageIds()).isEmpty();
        assertThat(details.translatedLanguages()).isEmpty();
        assertThatThrownBy(() -> details.embeddedImageIds().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("字数窄投影按 SQLite 安全批次读取且不触发其它详情查询")
    void shouldReadWordCountsInSafeBatchesWithoutHydratingOtherDetails() {
        NovelWorkDetailsRepository repository = new NovelWorkDetailsRepository(novelMapper);
        List<Long> ids = LongStream.rangeClosed(1, 501).boxed().toList();
        List<Long> request = new ArrayList<>(ids);
        request.add(1L);
        request.add(null);
        request.add(0L);
        when(novelMapper.findWordCountsByIds(ids.subList(0, 500))).thenReturn(List.of(
                new NovelMapper.NovelWordCountRow(1L, null),
                new NovelMapper.NovelWordCountRow(500L, 5000)));
        when(novelMapper.findWordCountsByIds(ids.subList(500, 501))).thenReturn(List.of(
                new NovelMapper.NovelWordCountRow(501L, 5010)));

        Map<Long, Integer> result = repository.findWordCounts(request);

        assertThat(result.keySet()).containsExactly(1L, 500L, 501L);
        assertThat(result.get(1L)).isNull();
        assertThat(result.get(500L)).isEqualTo(5000);
        assertThat(result.get(501L)).isEqualTo(5010);
        assertThatThrownBy(() -> result.put(2L, 20))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(novelMapper).findWordCountsByIds(ids.subList(0, 500));
        verify(novelMapper).findWordCountsByIds(ids.subList(500, 501));
        verifyNoMoreInteractions(novelMapper);
    }
}
