package top.sywyar.pixivdownload.novelgallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetails;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelBatchService 单元测试")
class NovelBatchServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private NovelGalleryService novelGalleryService;
    @Mock
    private WorkMetadataRepository workMetadataRepository;
    @Mock
    private NovelWorkDetailsRepository novelWorkDetailsRepository;
    @Mock
    private WorkAssetService workAssetService;
    @Mock
    private WorkCollectionMembership workCollectionMembership;
    @Mock
    private ArchiveExportService archiveExportService;

    private NovelBatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(archiveExportService.normalizeFormat(nullable(String.class))).thenAnswer(invocation -> {
            String normalized = ArchiveExportRules.normalizeFormatToken(invocation.getArgument(0));
            if (!ArchiveExportRules.supportsFormat(normalized)) {
                throw new IllegalArgumentException("unsupported format: " + normalized);
            }
            return normalized;
        });
        lenient().when(archiveExportService.export(any())).thenAnswer(invocation -> {
            ArchiveExportRequest request = invocation.getArgument(0);
            if (request.entries().isEmpty() || request.fileCount() <= 0) {
                return ArchiveExportResult.empty(request.workCount());
            }
            return new ArchiveExportResult(
                    "token-2", 3600L, request.workCount(), request.fileCount());
        });
        service = new NovelBatchService(novelGalleryService, workMetadataRepository,
                novelWorkDetailsRepository, workAssetService, workCollectionMembership,
                archiveExportService, new ObjectMapper());
    }

    @Test
    @DisplayName("ids 模式应去重、过滤非法 ID 并应用排除列表")
    void shouldResolveIdsMode() {
        NovelBatchRequest request = new NovelBatchRequest("ids",
                Arrays.asList(4L, 4L, 0L, null, 5L, 6L), List.of(5L), null, null, null, null, null);
        assertThat(service.resolveNovelIds(request)).containsExactly(4L, 6L);
    }

    @Test
    @DisplayName("filter 模式应委托小说画廊查询并应用排除列表")
    void shouldResolveFilterMode() {
        NovelBatchRequest.Filter filter = new NovelBatchRequest.Filter(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        NovelBatchRequest request = new NovelBatchRequest("filter",
                null, List.of(8L), filter, null, null, null, null);
        when(novelGalleryService.findNovelIds(any())).thenReturn(List.of(7L, 8L, 9L));

        assertThat(service.resolveNovelIds(request)).containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("批量收藏应只通过通用作品收藏端口并统计新增数量")
    void shouldCollectThroughWorkMembershipPort() {
        when(workCollectionMembership.addWork(WorkType.NOVEL, 9L, 1L)).thenReturn(true);
        when(workCollectionMembership.addWork(WorkType.NOVEL, 9L, 2L)).thenReturn(false);

        assertThat(service.collectNovels(Arrays.asList(1L, 1L, 0L, 2L), 9L)).isEqualTo(1);

        verify(workCollectionMembership).addWork(WorkType.NOVEL, 9L, 1L);
        verify(workCollectionMembership).addWork(WorkType.NOVEL, 9L, 2L);
    }

    @Test
    @DisplayName("不支持的打包格式应由核心归档端口校验并透传失败")
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> service.exportNovels(List.of(1L), "author", "7z", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7z");
        verify(archiveExportService).normalizeFormat("7z");
    }

    @Test
    @DisplayName("按作品 ID 打包时条目应以小说 ID 为顶层目录")
    void shouldGroupByNovelId() throws Exception {
        assertThat(exportSingleNovel("id", false).get(0).entryName())
                .isEqualTo("7/content.txt");
    }

    @Test
    @DisplayName("默认按作者分类打包，条目路径为 novels/{作者}/{ID - 标题}")
    void shouldGroupByAuthorByDefault() throws Exception {
        assertThat(exportSingleNovel("author", false).get(0).entryName())
                .isEqualTo("novels/Writer/7 - Story/content.txt");
    }

    @Test
    @DisplayName("勾选导出后删除时，应向核心归档端口提交小说删除指令")
    void shouldRequestDeleteAfterExport() throws Exception {
        ArchiveExportRequest request = exportSingleNovelRequest("author", true);

        assertThat(request.deleteAfterReady())
                .isEqualTo(new ArchiveWorkDeletion(WorkType.NOVEL.name(), List.of(7L)));
    }

    @Test
    @DisplayName("详情在并发软删除中缺席时跳过该小说且不读取或打包残留文件")
    void shouldSkipNovelWhoseOwnedDetailsDisappear() {
        WorkMetadata meta = new WorkMetadata(7L, "Story", null, 0, false,
                88L, "Writer", null, null, null, List.of(), 0L, 1, "txt", "/n/7",
                false, null, null, null, null, null, true);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L))).thenReturn(List.of(meta));
        when(novelWorkDetailsRepository.findAll(List.of(7L))).thenReturn(Map.of());

        ArchiveExportResult result =
                service.exportNovels(List.of(7L), "author", "zip", false);

        assertThat(result.emptyArchive()).isTrue();
        assertThat(result.workCount()).isEqualTo(1);
        verifyNoInteractions(workAssetService);
        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        assertThat(requestCaptor.getValue().entries()).isEmpty();
        assertThat(requestCaptor.getValue().workCount()).isEqualTo(1);
        assertThat(requestCaptor.getValue().fileCount()).isZero();
    }

    @Test
    @DisplayName("混合导出时删除指令只包含实际进入清单的小说")
    void shouldDeleteOnlyNovelsIncludedInManifest() throws Exception {
        Path folder = tempDir.resolve("novel-7");
        Files.createDirectories(folder);
        Path content = Files.writeString(folder.resolve("content.txt"), "text");
        WorkMetadata included = new WorkMetadata(7L, "Story", null, 0, false,
                88L, "Writer", null, null, null, List.of(), 0L, 1, "txt", folder.toString(),
                false, null, null, null, null, null, null);
        WorkMetadata missingDetails = new WorkMetadata(8L, "Missing", null, 0, false,
                89L, "Writer 2", null, null, null, List.of(), 0L, 1, "txt", "/n/8",
                false, null, null, null, null, null, null);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L, 8L)))
                .thenReturn(List.of(included, missingDetails));
        when(novelWorkDetailsRepository.findAll(List.of(7L, 8L))).thenReturn(Map.of(
                7L, new NovelWorkDetails(7L, 100, 200, 60, null, null, null,
                        List.of(), List.of())));
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, folder, 1,
                        List.of(new WorkAssetFile(0, content, "txt")))));
        ArchiveExportResult result =
                service.exportNovels(List.of(7L, 8L), "author", "zip", true);

        assertThat(result.archiveToken()).isEqualTo("token-2");
        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        assertThat(requestCaptor.getValue().deleteAfterReady())
                .isEqualTo(new ArchiveWorkDeletion(WorkType.NOVEL.name(), List.of(7L)));
    }

    private List<ArchiveExportEntry> exportSingleNovel(String groupBy, boolean deleteAfter)
            throws Exception {
        return exportSingleNovelRequest(groupBy, deleteAfter).entries();
    }

    private ArchiveExportRequest exportSingleNovelRequest(String groupBy, boolean deleteAfter)
            throws Exception {
        Path folder = tempDir.resolve("novel-7");
        Files.createDirectories(folder);
        Path content = Files.writeString(folder.resolve("content.txt"), "text");
        WorkMetadata meta = new WorkMetadata(7L, "Story", null, 0, false,
                88L, "Writer", null, null, null, List.of(), 0L, 1, "txt", folder.toString(),
                false, null, null, null, null, null, null);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L))).thenReturn(List.of(meta));
        when(novelWorkDetailsRepository.findAll(List.of(7L))).thenReturn(Map.of(
                7L, new NovelWorkDetails(7L, 100, 200, 60, null, null, null,
                        List.of(), List.of())));
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, folder, 1,
                        List.of(new WorkAssetFile(0, content, "txt")))));
        ArchiveExportResult result =
                service.exportNovels(List.of(7L), groupBy, "zip", deleteAfter);

        assertThat(result.archiveToken()).isEqualTo("token-2");
        assertThat(result.fileCount()).isEqualTo(1);

        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        ArchiveExportRequest request = requestCaptor.getValue();
        assertThat(request.exportType()).isEqualTo("novels");
        assertThat(request.workCount()).isEqualTo(1);
        assertThat(request.fileCount()).isEqualTo(1);
        assertThat(request.format()).isEqualTo("zip");
        return request;
    }
}
