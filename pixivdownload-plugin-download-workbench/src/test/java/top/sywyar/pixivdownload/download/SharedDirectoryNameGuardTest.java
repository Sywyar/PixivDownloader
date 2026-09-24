package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("共享目录下的文件名冲突防护")
class SharedDirectoryNameGuardTest {

    private static List<String> render(String template, long artworkId, String title, int count) {
        return PixivWorkFileNameFormatter.formatAll(template, artworkId, title, 1L, "author",
                1L, count, false, 0);
    }

    @Test
    @DisplayName("默认模板含 {artwork_id}，共享目录下安全")
    void defaultTemplateIsSafe() {
        assertThat(SharedDirectoryNameGuard.defaultTemplateIsSharedSafe()).isTrue();
        assertThat(SharedDirectoryNameGuard.declaresArtworkId(PixivWorkFileNameFormatter.DEFAULT_TEMPLATE))
                .isTrue();
    }

    @Test
    @DisplayName("不含 {artwork_id} 的模板在共享目录下不安全（不同作品会撞名）")
    void templateWithoutArtworkIdIsUnsafe() {
        String template = "{artwork_title}_p{page}";
        assertThat(SharedDirectoryNameGuard.declaresArtworkId(template)).isFalse();
        // 两个不同作品、相同标题 -> 渲染出完全相同的文件名，这正是必须拦截的情形
        assertThat(render(template, 111L, "same title", 1))
                .isEqualTo(render(template, 222L, "same title", 1));
    }

    @Test
    @DisplayName("含 {artwork_id} 时不同作品必然渲染出不同文件名，无论标题是否相同")
    void artworkIdMakesNamesUniqueAcrossArtworks() {
        for (String template : List.of(
                "{artwork_id}_p{page}",
                "({artwork_id}){artwork_title}_p{page}",
                "{artwork_title}_{artwork_id}_p{page}",
                "{artwork_id}")) {
            assertThat(SharedDirectoryNameGuard.declaresArtworkId(template))
                    .as("模板应被识别为含作品 ID: %s", template)
                    .isTrue();
            List<String> a = render(template, 111L, "same title", 1);
            List<String> b = render(template, 222L, "same title", 1);
            assertThat(a).as("模板 %s 下两个作品不应同名", template).isNotEqualTo(b);
        }
    }

    @Test
    @DisplayName("截断把作品 ID 截掉时判为不安全")
    void truncationLosingArtworkIdIsUnsafe() {
        // 模板把标题放前面，作品 ID 在末尾；标题极长时截断会切掉 ID
        String template = "{artwork_title}_{artwork_id}";
        long artworkId = 987654321L;
        String longTitle = "x".repeat(PixivWorkFileNameFormatter.MAX_BASENAME_LENGTH + 50);
        List<String> rendered = render(template, artworkId, longTitle, 1);

        assertThat(SharedDirectoryNameGuard.declaresArtworkId(template)).isTrue();
        assertThat(SharedDirectoryNameGuard.losesArtworkIdAfterTruncation(rendered, artworkId)).isTrue();
        assertThat(SharedDirectoryNameGuard.isSafeInSharedDirectory(template, artworkId, rendered)).isFalse();
    }

    @Test
    @DisplayName("正常长度下 ID 保留，判为安全")
    void artworkIdSurvivesAtNormalLength() {
        String template = "{artwork_title}_{artwork_id}_p{page}";
        long artworkId = 12345L;
        List<String> rendered = render(template, artworkId, "short", 2);

        assertThat(SharedDirectoryNameGuard.losesArtworkIdAfterTruncation(rendered, artworkId)).isFalse();
        assertThat(SharedDirectoryNameGuard.isSafeInSharedDirectory(template, artworkId, rendered)).isTrue();
    }

    @Test
    @DisplayName("空值与空列表不抛异常")
    void handlesEmptyInput() {
        assertThat(SharedDirectoryNameGuard.declaresArtworkId(null)).isFalse();
        assertThat(SharedDirectoryNameGuard.declaresArtworkId("")).isFalse();
        assertThat(SharedDirectoryNameGuard.losesArtworkIdAfterTruncation(List.of(), 1L)).isFalse();
        assertThat(SharedDirectoryNameGuard.isSafeInSharedDirectory("x", 1L, List.of())).isFalse();
    }
}
