package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DuplicateService 单元测试")
class DuplicateServiceTest {

    private final ImageHashMapper imageHashMapper = mock(ImageHashMapper.class);
    private final DuplicateService duplicateService =
            new DuplicateService(imageHashMapper, TestI18nBeans.appMessages());

    @Test
    @DisplayName("跨作品范围应聚合同一相似簇内的不同作品")
    void shouldGroupSimilarRowsAcrossArtworks() {
        stubRows(List.of(
                row(1001L, 0, 0b0000L, 0L),
                row(1002L, 0, 0b0011L, 0L),
                row(1003L, 0, 0xFFFF_0000_0000_0000L, 0L)
        ));

        DuplicateDto.GroupsPage page = duplicateService.groups(2, 2, "cross-artwork", 0, 20);

        assertThat(page.totalGroups()).isEqualTo(1);
        assertThat(page.groups()).singleElement()
                .satisfies(group -> {
                    assertThat(group.size()).isEqualTo(2);
                    assertThat(group.maxDistance()).isEqualTo(2);
                    assertThat(group.items()).extracting(DuplicateDto.Item::artworkId)
                            .containsExactly(1001L, 1002L);
                });
    }

    @Test
    @DisplayName("cross-artwork 范围应过滤同一作品内的多页相似图")
    void shouldFilterSingleArtworkGroupsInCrossArtworkScope() {
        stubRows(List.of(
                row(1001L, 0, 0L, 0L),
                row(1001L, 1, 1L, 0L)
        ));

        DuplicateDto.GroupsPage crossArtwork = duplicateService.groups(2, 2, "cross-artwork", 0, 20);
        DuplicateDto.GroupsPage all = duplicateService.groups(2, 2, "all", 0, 20);

        assertThat(crossArtwork.groups()).isEmpty();
        assertThat(all.groups()).singleElement()
                .extracting(DuplicateDto.Group::size)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("aHash 距离超过阈值时不应合并 dHash 接近的图片")
    void shouldRespectAHashThreshold() {
        stubRows(List.of(
                row(1001L, 0, 0L, 0L),
                row(1002L, 0, 1L, -1L)
        ));

        DuplicateDto.GroupsPage page = duplicateService.groups(2, 0, "cross-artwork", 0, 20);

        assertThat(page.groups()).isEmpty();
    }

    @Test
    @DisplayName("阈值参数越界应返回明确错误")
    void shouldRejectInvalidThreshold() {
        assertThatThrownBy(() -> duplicateService.groups(33, 2, "cross-artwork", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    private void stubRows(List<ImageHashRow> rows) {
        when(imageHashMapper.countAllHashRows()).thenReturn((long) rows.size());
        when(imageHashMapper.maxCreatedTime()).thenReturn(123L);
        when(imageHashMapper.findAll()).thenReturn(rows);
    }

    private static ImageHashRow row(long artworkId, int page, long dHash, Long aHash) {
        return new ImageHashRow(
                artworkId,
                page,
                "jpg",
                dHash,
                aHash,
                123L,
                "title-" + artworkId,
                9000L + artworkId,
                "author-" + artworkId,
                0
        );
    }
}
