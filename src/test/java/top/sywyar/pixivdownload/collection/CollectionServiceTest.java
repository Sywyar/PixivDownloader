package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PathPrefixCodec;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionService 单元测试")
class CollectionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private CollectionMapper collectionMapper;
    @Mock
    private CollectionIconService iconService;
    @Mock
    private DownloadConfig downloadConfig;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private PathPrefixCodec pathPrefixCodec;

    private CollectionService collectionService;

    @BeforeEach
    void setUp() {
        lenient().when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        lenient().when(pathPrefixCodec.encode(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pathPrefixCodec.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        collectionService = new CollectionService(
                collectionMapper,
                iconService,
                TestI18nBeans.appMessages(),
                downloadConfig,
                novelDatabase,
                pathPrefixCodec
        );
    }

    @Nested
    @DisplayName("download root")
    class DownloadRootTests {

        @Test
        @DisplayName("应保留 UTF-8 字符并清理模板中的不安全路径分隔符")
        void shouldResolveUtf8TemplateAsSafeRelativePath() {
            when(collectionMapper.findById(7L)).thenReturn(new Collection(
                    7L, "中文😀/unsafe", null, "{collection_name}", 0, 1700000000L, 0, 0
            ));

            Path resolved = collectionService.resolveDownloadRoot(7L, tempDir);

            assertThat(resolved).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("中文😀_unsafe"));
        }

        @Test
        @DisplayName("单斜杠开头的目录应按 download.root-folder 下的相对目录处理")
        void shouldResolveSingleSlashPrefixedPathAsRelativePath() {
            when(collectionMapper.findById(7L)).thenReturn(new Collection(
                    7L, "safe", null, "/src/main/test", 0, 1700000000L, 0, 0
            ));

            Path resolved = collectionService.resolveDownloadRoot(7L, tempDir);

            assertThat(resolved).isEqualTo(tempDir.toAbsolutePath().normalize().resolve("src/main/test"));
        }

        @Test
        @DisplayName("相对目录不能逃出 download.root-folder")
        void shouldRejectRelativePathEscapingRootFolder() {
            when(collectionMapper.findById(7L)).thenReturn(new Collection(
                    7L, "safe", null, null, 0, 1700000000L, 0, 0
            ));

            assertThatThrownBy(() -> collectionService.updateDownloadRoot(7L, "../outside"))
                    .isInstanceOf(LocalizedException.class);

            verify(collectionMapper, never()).updateDownloadRoot(7L, "../outside");
        }

        @Test
        @DisplayName("绝对目录应原样保存")
        void shouldAcceptAbsoluteDownloadRoot() {
            Path absoluteRoot = tempDir.resolve("absolute-root").toAbsolutePath();
            when(collectionMapper.findById(7L))
                    .thenReturn(new Collection(7L, "safe", null, null, 0, 1700000000L, 0, 0))
                    .thenReturn(new Collection(7L, "safe", null, absoluteRoot.toString(), 0, 1700000000L, 0, 0));

            collectionService.updateDownloadRoot(7L, absoluteRoot.toString());

            verify(collectionMapper).updateDownloadRoot(7L, absoluteRoot.toString());
        }
    }

    @Nested
    @DisplayName("create / rename 名称校验")
    class NameValidationTests {

        @Test
        @DisplayName("空名应被拒绝")
        void shouldRejectBlankName() {
            assertThatThrownBy(() -> collectionService.create("   "))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                            .isEqualTo("validation.collection.name.required"));

            verify(collectionMapper, never()).insert(any());
        }

        @Test
        @DisplayName("超长名应被拒绝")
        void shouldRejectTooLongName() {
            String tooLong = "a".repeat(CollectionService.MAX_NAME_LENGTH + 1);

            assertThatThrownBy(() -> collectionService.create(tooLong))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                            .isEqualTo("collection.name.too-long"));
        }

        @Test
        @DisplayName("大小写不敏感重名应被拒绝")
        void shouldRejectDuplicateNameIgnoreCase() {
            when(collectionMapper.countByName("MyName")).thenReturn(1);

            assertThatThrownBy(() -> collectionService.create("MyName"))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                            .isEqualTo("collection.name.duplicate"));

            verify(collectionMapper, never()).insert(any());
        }

        @Test
        @DisplayName("rename 时同名（排除自身）应被拒绝")
        void shouldRejectRenameToDuplicateName() {
            when(collectionMapper.countById(7L)).thenReturn(1);
            when(collectionMapper.countByNameExcludingId("Other", 7L)).thenReturn(1);

            assertThatThrownBy(() -> collectionService.rename(7L, "Other"))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                            .isEqualTo("collection.name.duplicate"));

            verify(collectionMapper, never()).updateName(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("addArtwork 幂等")
    class AddArtworkIdempotencyTests {

        @Test
        @DisplayName("INSERT OR IGNORE 第一次插入应返回 true")
        void shouldReturnTrueOnFirstInsert() {
            when(collectionMapper.countById(1L)).thenReturn(1);
            when(collectionMapper.addArtwork(eq(1L), eq(99L), anyLong())).thenReturn(1);

            boolean changed = collectionService.addArtwork(1L, 99L);

            assertThat(changed).isTrue();
        }

        @Test
        @DisplayName("再次插入相同关系应返回 false（INSERT OR IGNORE 命中）")
        void shouldReturnFalseOnDuplicateInsert() {
            when(collectionMapper.countById(1L)).thenReturn(1);
            when(collectionMapper.addArtwork(eq(1L), eq(99L), anyLong())).thenReturn(0);

            boolean changed = collectionService.addArtwork(1L, 99L);

            assertThat(changed).isFalse();
        }

        @Test
        @DisplayName("收藏夹不存在时应抛 LocalizedException")
        void shouldRejectAddArtworkWhenCollectionMissing() {
            when(collectionMapper.countById(404L)).thenReturn(0);

            assertThatThrownBy(() -> collectionService.addArtwork(404L, 99L))
                    .isInstanceOf(LocalizedException.class)
                    .satisfies(e -> assertThat(((LocalizedException) e).getMessageCode())
                            .isEqualTo("collection.not-found"));

            verify(collectionMapper, never()).addArtwork(anyLong(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("delete 联表清理 + 图标清理")
    class DeleteCleanupTests {

        @Test
        @DisplayName("删除收藏夹应先清理 artwork_collections 关系，再删主表，再删图标文件")
        void shouldCleanupRelationsAndIconOnDelete() {
            when(collectionMapper.countById(5L)).thenReturn(1);

            collectionService.delete(5L);

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(collectionMapper, iconService);
            inOrder.verify(collectionMapper).deleteArtworkLinksByCollection(5L);
            inOrder.verify(collectionMapper).deleteById(5L);
            inOrder.verify(iconService).deleteAll(5L);
        }

        @Test
        @DisplayName("收藏夹不存在时不应触达任何清理动作")
        void shouldNotTouchAnythingWhenCollectionMissing() {
            when(collectionMapper.countById(5L)).thenReturn(0);

            assertThatThrownBy(() -> collectionService.delete(5L))
                    .isInstanceOf(LocalizedException.class);

            verify(collectionMapper, never()).deleteArtworkLinksByCollection(anyLong());
            verify(collectionMapper, never()).deleteById(anyLong());
            verify(iconService, never()).deleteAll(anyLong());
        }
    }

    @Nested
    @DisplayName("memberships 合并")
    class MembershipsTests {

        @Test
        @DisplayName("应把扁平 row 合并为 artworkId → collectionIds 映射")
        void shouldMergeFlatRowsIntoMap() {
            java.util.Map<String, Object> row1 = new java.util.HashMap<>();
            row1.put("artworkId", 100L);
            row1.put("collectionId", 1L);
            java.util.Map<String, Object> row2 = new java.util.HashMap<>();
            row2.put("artworkId", 100L);
            row2.put("collectionId", 2L);
            java.util.Map<String, Object> row3 = new java.util.HashMap<>();
            row3.put("artworkId", 200L);
            row3.put("collectionId", 1L);
            when(collectionMapper.findLinksByArtworks(List.of(100L, 200L)))
                    .thenReturn(List.of(row1, row2, row3));

            java.util.Map<Long, java.util.List<Long>> result =
                    collectionService.membershipsOf(List.of(100L, 200L));

            assertThat(result).hasSize(2);
            assertThat(result.get(100L)).containsExactly(1L, 2L);
            assertThat(result.get(200L)).containsExactly(1L);
        }

        @Test
        @DisplayName("入参为空时应返回空 map 且不查询数据库")
        void shouldReturnEmptyMapForEmptyInput() {
            java.util.Map<Long, java.util.List<Long>> result =
                    collectionService.membershipsOf(java.util.List.of());

            assertThat(result).isEmpty();
            verify(collectionMapper, never()).findLinksByArtworks(any());
        }
    }
}
