package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRow;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("访客小说年龄分级守卫")
class GuestAccessGuardTest {

    @ParameterizedTest(name = "小说分级 {0}，访客仅允许 {1} => {2}")
    @CsvSource({
            "0, 0, true",
            "0, 1, false",
            "0, 2, false",
            "1, 0, false",
            "1, 1, true",
            "1, 2, false",
            "2, 0, false",
            "2, 1, false",
            "2, 2, true"
    })
    @DisplayName("SFW、R18 与 R18G 应严格匹配访客允许范围")
    void novelRatingMustMatchGuestPermission(int novelRating, int allowedRating, boolean expected) {
        NovelMetadataRepository novelMetadataRepository = mock(NovelMetadataRepository.class);
        GuestAccessGuard guard = new GuestAccessGuard(mock(PixivDatabase.class), novelMetadataRepository);
        when(novelMetadataRepository.getNovel(42L)).thenReturn(novelRecord(novelRating));
        when(novelMetadataRepository.getNovelTags(42L)).thenReturn(List.of());

        GuestInviteSession session = new GuestInviteSession(
                1L, "invite",
                allowedRating == 0, allowedRating == 1, allowedRating == 2,
                true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of());

        assertThat(guard.isNovelVisibleToGuest(42L, session)).isEqualTo(expected);
    }

    private static NovelMetadataRow novelRecord(int xRestrict) {
        return new NovelMetadataRow(
                42L, "title", "folder", 1, "txt", 1L,
                xRestrict, false, 7L, "", 1L, null,
                null, null, 10, 10, 10, 1,
                true, "ja", null);
    }
}
