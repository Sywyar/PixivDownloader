package top.sywyar.pixivdownload.collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private PathPrefixCodec pathPrefixCodec;

    private CollectionService collectionService;

    @ParameterizedTest
    @ValueSource(strings = {"replace", "clear", "delete"})
    @DisplayName("图标发布尚未结束时，后续替换、清空和删除均等待提交")
    void shouldSerializeIconMutations(String operation) throws Exception {
        when(collectionMapper.countById(1L)).thenReturn(1);
        when(collectionMapper.findById(1L)).thenReturn(new Collection(1L, "first", null, null, 0, 1, 0, 0));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(iconService.saveIcon(eq(1L), any(), any())).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return "png";
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var upload = executor.submit(() -> collectionService.setIcon(1, new byte[]{1}));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var following = executor.submit(() -> {
                started.countDown();
                switch (operation) {
                    case "replace" -> collectionService.setIcon(1, new byte[]{2});
                    case "clear" -> collectionService.clearIcon(1);
                    case "delete" -> collectionService.delete(1);
                    default -> throw new AssertionError(operation);
                }
                return null;
            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> following.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            upload.get(5, java.util.concurrent.TimeUnit.SECONDS);
            following.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

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
                novelMetadataRepository,
                pathPrefixCodec,
                org.mockito.Mockito.mock(top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer.class)
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
        @DisplayName("收藏夹不存在时不应触达任何清理动作")
        void shouldNotTouchAnythingWhenCollectionMissing() throws Exception {
            when(collectionMapper.countById(5L)).thenReturn(0);

            assertThatThrownBy(() -> collectionService.delete(5L))
                    .isInstanceOf(LocalizedException.class);

            verify(collectionMapper, never()).deleteById(anyLong());
            verify(iconService, never()).deleteAll(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("memberships 合并")
    class MembershipsTests {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("插画与小说归属均按作品分组并保留收藏夹顺序")
        void shouldMergeFlatRowsIntoMap(boolean novel) {
            String workIdField = novel ? "novelId" : "artworkId";
            List<Map<String, Object>> rows = List.of(
                    Map.of(workIdField, 100L, "collectionId", 1L),
                    Map.of(workIdField, 100L, "collectionId", 2L),
                    Map.of(workIdField, 200L, "collectionId", 1L));
            if (novel) {
                when(novelMetadataRepository.findCollectionLinksByNovels(List.of(100L, 200L))).thenReturn(rows);
            } else {
                when(collectionMapper.findLinksByArtworks(List.of(100L, 200L))).thenReturn(rows);
            }

            Map<Long, List<Long>> result = novel
                    ? collectionService.novelMembershipsOf(List.of(100L, 200L))
                    : collectionService.membershipsOf(List.of(100L, 200L));

            assertThat(result).hasSize(2);
            assertThat(result.get(100L)).containsExactly(1L, 2L);
            assertThat(result.get(200L)).containsExactly(1L);
        }

        @Test
        @DisplayName("入参为空时应返回空 map 且不查询数据库")
        void shouldReturnEmptyMapForEmptyInput() {
            assertThat(collectionService.membershipsOf(List.of())).isEmpty();
            assertThat(collectionService.membershipsOf(null)).isEmpty();
            assertThat(collectionService.novelMembershipsOf(List.of())).isEmpty();
            assertThat(collectionService.novelMembershipsOf(null)).isEmpty();
            verify(collectionMapper, never()).findLinksByArtworks(any());
            verify(novelMetadataRepository, never()).findCollectionLinksByNovels(any());
        }
    }
}
