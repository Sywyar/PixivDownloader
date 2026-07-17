package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryBatchService 单元测试")
class GalleryBatchServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private GalleryService galleryService;
    @Mock
    private WorkMetadataRepository workMetadataRepository;
    @Mock
    private WorkAssetService workAssetService;
    @Mock
    private CollectionService collectionService;
    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private MultiModeSettings multiModeSettings;

    private GalleryBatchService service;

    @BeforeEach
    void setUp() {
        service = new GalleryBatchService(galleryService, workMetadataRepository, workAssetService,
                collectionService, userQuotaService, multiModeSettings, new ObjectMapper());
    }

    @Test
    @DisplayName("ids 模式应去重、过滤非法 ID 并应用排除列表")
    void shouldResolveIdsMode() {
        ArtworkBatchRequest request = new ArtworkBatchRequest("ids",
                Arrays.asList(1L, 1L, 0L, null, 2L, 3L), List.of(2L), null, null, null, null, null);
        assertThat(service.resolveArtworkIds(request)).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("filter 模式应委托画廊筛选查询并应用排除列表")
    void shouldResolveFilterMode() {
        ArtworkBatchRequest.Filter filter = new ArtworkBatchRequest.Filter(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        ArtworkBatchRequest request = new ArtworkBatchRequest("filter",
                null, List.of(6L), filter, null, null, null, null);
        when(galleryService.findArtworkIds(any())).thenReturn(List.of(5L, 6L, 7L));

        assertThat(service.resolveArtworkIds(request)).containsExactly(5L, 7L);
    }

    @Test
    @DisplayName("不支持的打包格式应抛出 400")
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> service.exportArtworks(List.of(1L), "author", "rar", false))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("空 ID 集合应返回空导出结果且不触发打包")
    void shouldReturnEmptyResultForNoIds() {
        ArchiveExportSupport.ExportResult result = service.exportArtworks(List.of(), null, null, false);
        assertThat(result.emptyArchive()).isTrue();
    }

    @Test
    @DisplayName("默认按作者分类打包，条目路径为 artworks/{作者}/{ID - 标题}")
    void shouldGroupByAuthorByDefault() throws Exception {
        List<UserQuotaService.ArchiveItem> items = exportSingleArtwork("author", false);
        assertThat(items.get(0).entryName()).isEqualTo("artworks/Artist/10 - Title/file.jpg");
        assertThat(items.get(items.size() - 1).entryName()).isEqualTo("manifest.json");
    }

    @Test
    @DisplayName("按作品 ID 打包时条目应以作品 ID 为顶层目录")
    void shouldGroupByArtworkId() throws Exception {
        List<UserQuotaService.ArchiveItem> items = exportSingleArtwork("id", false);
        assertThat(items.get(0).entryName()).isEqualTo("10/file.jpg");
    }

    @Test
    @DisplayName("勾选导出后删除时，打包成功回调应执行批量删除")
    void shouldDeleteAfterExportViaCallback() throws Exception {
        ArgumentCaptor<Runnable> afterReady = ArgumentCaptor.forClass(Runnable.class);
        exportSingleArtwork("author", true, afterReady);
        assertThat(afterReady.getValue()).isNotNull();
        afterReady.getValue().run();
        verify(galleryService).deleteArtworks(List.of(10L));
    }

    private List<UserQuotaService.ArchiveItem> exportSingleArtwork(String groupBy, boolean deleteAfter)
            throws Exception {
        return exportSingleArtwork(groupBy, deleteAfter, ArgumentCaptor.forClass(Runnable.class));
    }

    @SuppressWarnings("unchecked")
    private List<UserQuotaService.ArchiveItem> exportSingleArtwork(String groupBy, boolean deleteAfter,
                                                                   ArgumentCaptor<Runnable> afterReady)
            throws Exception {
        WorkMetadata meta = new WorkMetadata(WorkType.ARTWORK, 10L, "Title", null, 0, false,
                99L, "Artist", null, null, null, List.of(), 0L, 1, "jpg", tempDir.toString(),
                false, null, null, null, null, null, null, null, null);
        Path image = Files.createFile(tempDir.resolve("file.jpg"));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(10L))).thenReturn(List.of(meta));
        when(workAssetService.findAsset(WorkType.ARTWORK, 10L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.ARTWORK, 10L, tempDir, 1,
                        List.of(new WorkAssetFile(0, image, "jpg")))));
        when(userQuotaService.triggerAdminFileArchive(anyList(), anyString(), anyInt(), any()))
                .thenReturn("token-1");
        when(multiModeSettings.getArchiveExpireMinutes()).thenReturn(60);

        ArchiveExportSupport.ExportResult result =
                service.exportArtworks(List.of(10L), groupBy, "zip", deleteAfter);

        assertThat(result.archiveToken()).isEqualTo("token-1");
        assertThat(result.workCount()).isEqualTo(1);
        assertThat(result.fileCount()).isEqualTo(1);
        assertThat(result.archiveExpireSeconds()).isEqualTo(3600);

        ArgumentCaptor<List<UserQuotaService.ArchiveItem>> itemsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(userQuotaService).triggerAdminFileArchive(
                itemsCaptor.capture(), eq("artworks"), eq(1), afterReady.capture());
        return itemsCaptor.getValue();
    }
}
