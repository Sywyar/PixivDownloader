package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
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
import static org.mockito.Mockito.times;
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
    private WorkCollectionMembership collectionMembership;
    @Mock
    private ArchiveExportService archiveExportService;

    private GalleryBatchService service;

    @BeforeEach
    void setUp() {
        service = new GalleryBatchService(galleryService, workMetadataRepository, workAssetService,
                collectionMembership, archiveExportService, new ObjectMapper());
    }

    @Test
    @DisplayName("批量筛选从 JSON 完整传递两种日期范围")
    void forwardsDateRangesFromJson() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"mode":"filter","filter":{"sort":"date","order":"asc",
                  "downloadedFrom":100,"downloadedBefore":200,
                  "uploadedFrom":300,"uploadedBefore":400}}
                """, ArtworkBatchRequest.class);
        when(galleryService.findArtworkIds(any())).thenReturn(List.of(7L));
        assertThat(service.resolveArtworkIds(request)).containsExactly(7L);
        var captured = ArgumentCaptor.forClass(WorkQuery.class);
        verify(galleryService).findArtworkIds(captured.capture());
        assertThat(captured.getValue().downloadedFrom()).isEqualTo(100L);
        assertThat(captured.getValue().downloadedBefore()).isEqualTo(200L);
        assertThat(captured.getValue().uploadedFrom()).isEqualTo(300L);
        assertThat(captured.getValue().uploadedBefore()).isEqualTo(400L);
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
                List.of("JPG", "invalid"), Arrays.asList(9L, null, 0L, 9L),
                Arrays.asList(12L, 11L, 12L, 0L, null), List.of(-1L, 13L, 13L),
                Arrays.asList(null, 15L, 14L, 15L), List.of(21L), List.of(31L, 0L, 31L),
                Arrays.asList(42L, null, 41L, 42L), 22L, List.of(51L, 0L, 51L),
                Arrays.asList(null, 61L, -1L, 61L), 52L);
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
        assertThat(query.collectionIds()).containsExactly(9L);
        assertThat(query.tagIds()).containsExactly(12L, 11L);
        assertThat(query.excludedTagIds()).containsExactly(13L);
        assertThat(query.optionalTagIds()).containsExactly(15L, 14L);
        assertThat(query.authorIds()).containsExactly(21L, 22L);
        assertThat(query.excludedAuthorIds()).containsExactly(31L);
        assertThat(query.optionalAuthorIds()).containsExactly(42L, 41L);
        assertThat(query.seriesIds()).containsExactly(51L, 52L);
        assertThat(query.excludedSeriesIds()).containsExactly(61L);
    }

    @Test
    @DisplayName("缺失、空和全非法 ID 筛选均不产生查询条件")
    void emptyIdFiltersRemainAbsent() {
        for (List<Long> ids : Arrays.<List<Long>>asList(null, List.of(), Arrays.asList(null, 0L, -1L))) {
            ArtworkBatchRequest.Filter filter = new ArtworkBatchRequest.Filter(
                    null, null, null, null, null, null, null, null,
                    null, ids, ids, ids, ids, ids, ids, ids, null, ids, ids, null);
            service.resolveArtworkIds(new ArtworkBatchRequest("filter",
                    null, null, filter, null, null, null, null));
        }
        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(galleryService, times(3)).findArtworkIds(captor.capture());
        for (WorkQuery query : captor.getAllValues()) {
            assertThat(query.collectionIds()).isNull();
            assertThat(query.tagIds()).isNull();
            assertThat(query.excludedTagIds()).isNull();
            assertThat(query.optionalTagIds()).isNull();
            assertThat(query.authorIds()).isNull();
            assertThat(query.excludedAuthorIds()).isNull();
            assertThat(query.optionalAuthorIds()).isNull();
            assertThat(query.seriesIds()).isNull();
            assertThat(query.excludedSeriesIds()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "' artworkId ', ' tagExact ', artworkId, tagExact",
            "ARTWORKID, TAGEXACT, date, all",
            "' ', ' ', date, all",
            ", , date, all",
            "series, authorId, series, authorId",
            "unexpected, unexpected, date, all"
    })
    @DisplayName("排序和搜索类型保留大小写规则、去空白与独立默认值")
    void preservesQueryChoices(String sort, String searchType, String expectedSort, String expectedSearchType) {
        ArtworkBatchRequest.Filter filter = new ArtworkBatchRequest.Filter(
                null, null, sort, null, null, searchType, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        service.resolveArtworkIds(new ArtworkBatchRequest("filter",
                null, null, filter, null, null, null, null));
        ArgumentCaptor<WorkQuery> captor = ArgumentCaptor.forClass(WorkQuery.class);
        verify(galleryService).findArtworkIds(captor.capture());
        assertThat(captor.getValue().sort()).isEqualTo(expectedSort);
        assertThat(captor.getValue().searchType()).isEqualTo(expectedSearchType);
    }

    @Test
    @DisplayName("批量收藏应只通过核心收藏成员端口并统计新增数量")
    void shouldCollectThroughCoreMembershipPort() {
        when(collectionMembership.addWork(WorkType.ARTWORK, 9L, 1L)).thenReturn(true);
        when(collectionMembership.addWork(WorkType.ARTWORK, 9L, 2L)).thenReturn(false);

        assertThat(service.collectArtworks(Arrays.asList(1L, 1L, 0L, 2L), 9L)).isEqualTo(1);

        verify(collectionMembership).addWork(WorkType.ARTWORK, 9L, 1L);
        verify(collectionMembership).addWork(WorkType.ARTWORK, 9L, 2L);
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
        WorkMetadata meta = new WorkMetadata(10L, "Title", null, 0, false,
                99L, "Artist", null, null, null, List.of(), 0L, 1, "jpg", tempDir.toString(),
                false, null, null, null, null, null);
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
