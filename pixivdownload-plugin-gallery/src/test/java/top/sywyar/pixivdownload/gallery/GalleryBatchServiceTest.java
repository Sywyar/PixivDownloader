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
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.collection.ArtworkCollectionMembership;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ArtworkCollectionMembership collectionMembership;
    @Mock
    private ArchiveExportService archiveExportService;

    private GalleryBatchService service;

    @BeforeEach
    void setUp() {
        service = new GalleryBatchService(galleryService, workMetadataRepository, workAssetService,
                collectionMembership, archiveExportService, new ObjectMapper());
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
                null, null, "artworkId", "asc", "  needle  ", "title", "r18", "yes",
                List.of("JPG", "invalid"), null, List.of(11L, 11L, 0L), null, null,
                List.of(21L), null, null, 22L, null, null, null);
        ArtworkBatchRequest request = new ArtworkBatchRequest("filter",
                null, List.of(6L), filter, null, null, null, null);
        when(galleryService.findArtworkIds(any())).thenReturn(List.of(5L, 6L, 7L));

        assertThat(service.resolveArtworkIds(request)).containsExactly(5L, 7L);

        ArgumentCaptor<WorkQuery> queryCaptor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(galleryService).findArtworkIds(queryCaptor.capture());
        WorkQuery query = queryCaptor.getValue();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(200);
        assertThat(query.sort()).isEqualTo("artworkId");
        assertThat(query.order()).isEqualTo("asc");
        assertThat(query.search()).isEqualTo("needle");
        assertThat(query.searchType()).isEqualTo("title");
        assertThat(query.r18()).isEqualTo("r18");
        assertThat(query.ai()).isEqualTo("yes");
        assertThat(query.formats()).containsExactly("jpg");
        assertThat(query.tagIds()).containsExactly(11L);
        assertThat(query.authorIds()).containsExactly(21L, 22L);
    }

    @Test
    @DisplayName("批量收藏应只通过核心收藏成员端口并统计新增数量")
    void shouldCollectThroughCoreMembershipPort() {
        when(collectionMembership.addArtwork(9L, 1L)).thenReturn(true);
        when(collectionMembership.addArtwork(9L, 2L)).thenReturn(false);

        assertThat(service.collectArtworks(Arrays.asList(1L, 1L, 0L, 2L), 9L)).isEqualTo(1);

        verify(collectionMembership).addArtwork(9L, 1L);
        verify(collectionMembership).addArtwork(9L, 2L);
    }

    @Test
    @DisplayName("格式校验异常应在读取作品元数据前由核心归档端口原样传播")
    void shouldPropagateArchiveFormatValidation() {
        IllegalArgumentException invalidFormat = new IllegalArgumentException("unsupported format");
        when(archiveExportService.normalizeFormat("rar")).thenThrow(invalidFormat);

        assertThatThrownBy(() -> service.exportArtworks(List.of(1L), "author", "rar", false))
                .isSameAs(invalidFormat);
        verifyNoInteractions(workMetadataRepository, workAssetService);
    }

    @Test
    @DisplayName("空 ID 集合应把空导出请求交给核心归档端口")
    void shouldDelegateEmptyResultForNoIds() {
        when(archiveExportService.normalizeFormat(null)).thenReturn("zip");
        when(archiveExportService.export(any())).thenReturn(ArchiveExportResult.empty());

        ArchiveExportResult result = service.exportArtworks(List.of(), null, null, false);

        assertThat(result.emptyArchive()).isTrue();
        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        assertThat(requestCaptor.getValue().entries()).isEmpty();
        assertThat(requestCaptor.getValue().workCount()).isZero();
        assertThat(requestCaptor.getValue().fileCount()).isZero();
    }

    @Test
    @DisplayName("默认按作者分类打包，条目路径为 artworks/{作者}/{ID - 标题}")
    void shouldGroupByAuthorByDefault() throws Exception {
        ArchiveExportRequest request = exportSingleArtwork("author", false);
        assertThat(request.entries().get(0).entryName())
                .isEqualTo("artworks/Artist/10 - Title/file.jpg");
        assertThat(request.entries().get(request.entries().size() - 1).entryName())
                .isEqualTo("manifest.json");
    }

    @Test
    @DisplayName("按作品 ID 打包时条目应以作品 ID 为顶层目录")
    void shouldGroupByArtworkId() throws Exception {
        ArchiveExportRequest request = exportSingleArtwork("id", false);
        assertThat(request.entries().get(0).entryName()).isEqualTo("10/file.jpg");
    }

    @Test
    @DisplayName("归档请求应保留作品 ID、manifest 和作品文件")
    void shouldPreserveManifestAndWorkIdentity() throws Exception {
        ArchiveExportRequest request = exportSingleArtwork("author", false);

        ArchiveExportEntry file = request.entries().get(0);
        ArchiveExportEntry manifest = request.entries().get(1);
        assertThat(file.sourcePath()).isEqualTo(tempDir.resolve("file.jpg"));
        assertThat(file.workId()).isEqualTo(10L);
        assertThat(manifest.entryName()).isEqualTo("manifest.json");
        assertThat(new String(manifest.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"type\" : \"artwork\"")
                .contains("\"id\" : 10");
    }

    @Test
    @DisplayName("勾选导出后删除时应只向宿主提交纯值删除指令")
    void shouldRequestDeleteAfterExportWithoutPluginCallback() throws Exception {
        ArchiveExportRequest request = exportSingleArtwork("author", true);

        assertThat(request.deleteAfterReady()).isNotNull();
        assertThat(request.deleteAfterReady().workType()).isEqualTo(WorkType.ARTWORK.name());
        assertThat(request.deleteAfterReady().workIds()).containsExactly(10L);
    }

    private ArchiveExportRequest exportSingleArtwork(String groupBy, boolean deleteAfter) throws Exception {
        WorkMetadata meta = new WorkMetadata(WorkType.ARTWORK, 10L, "Title", null, 0, false,
                99L, "Artist", null, null, null, List.of(), 0L, 1, "jpg", tempDir.toString(),
                false, null, null, null, null, null, null, null);
        Path image = Files.createFile(tempDir.resolve("file.jpg"));
        when(workMetadataRepository.findAll(WorkType.ARTWORK, List.of(10L))).thenReturn(List.of(meta));
        when(workAssetService.findAsset(WorkType.ARTWORK, 10L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.ARTWORK, 10L, tempDir, 1,
                        List.of(new WorkAssetFile(0, image, "jpg")))));
        ArchiveExportResult expected = new ArchiveExportResult("token-1", 3600, 1, 1);
        when(archiveExportService.normalizeFormat("zip")).thenReturn("zip");
        when(archiveExportService.export(any())).thenReturn(expected);

        ArchiveExportResult result =
                service.exportArtworks(List.of(10L), groupBy, "zip", deleteAfter);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        ArchiveExportRequest request = requestCaptor.getValue();
        assertThat(request.exportType()).isEqualTo("artworks");
        assertThat(request.workCount()).isEqualTo(1);
        assertThat(request.fileCount()).isEqualTo(1);
        assertThat(request.format()).isEqualTo("zip");
        return request;
    }
}
