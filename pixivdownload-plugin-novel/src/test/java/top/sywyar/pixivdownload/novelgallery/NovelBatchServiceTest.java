package top.sywyar.pixivdownload.novelgallery;

import tools.jackson.databind.json.JsonMapper;
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

    @Mock
    private top.sywyar.pixivdownload.novel.export.NovelExportService novelExportService;

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
                archiveExportService, JsonMapper.builderWithJackson2Defaults().build(), novelExportService);
    }

    @Test
    @DisplayName("批量筛选从 JSON 完整传递多种日期范围")
    void forwardsDateRangesFromJson() throws Exception {
        var request = JsonMapper.builderWithJackson2Defaults().build().readValue("""
                {"mode":"filter","filter":{"sort":"date","order":"asc",
                  "downloadedFrom":100,"downloadedBefore":200,
                  "uploadedFrom":300,"uploadedBefore":400,
                  "createdFrom":500,"createdBefore":600,"updatedFrom":700,"updatedBefore":800,
                  "reuploadedFrom":900,"reuploadedBefore":1000}}
                """, NovelBatchRequest.class);
        when(novelGalleryService.findNovelIds(any())).thenReturn(List.of(7L));
        assertThat(service.resolveNovelIds(request)).containsExactly(7L);
        var captured = ArgumentCaptor.forClass(NovelGalleryService.NovelGalleryQuery.class);
        verify(novelGalleryService).findNovelIds(captured.capture());
        assertThat(captured.getValue().downloadedFrom()).isEqualTo(100L);
        assertThat(captured.getValue().downloadedBefore()).isEqualTo(200L);
        assertThat(captured.getValue().uploadedFrom()).isEqualTo(300L);
        assertThat(captured.getValue().uploadedBefore()).isEqualTo(400L);
        assertThat(captured.getValue().createdFrom()).isEqualTo(500L);
        assertThat(captured.getValue().createdBefore()).isEqualTo(600L);
        assertThat(captured.getValue().updatedFrom()).isEqualTo(700L);
        assertThat(captured.getValue().updatedBefore()).isEqualTo(800L);
        assertThat(captured.getValue().reuploadedFrom()).isEqualTo(900L);
        assertThat(captured.getValue().reuploadedBefore()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("ids 模式应去重、过滤非法 ID 并应用排除列表")
    void shouldResolveIdsMode() {
        NovelBatchRequest request = new NovelBatchRequest("ids",
                Arrays.asList(4L, 4L, 0L, null, 5L, 6L), List.of(5L), null, null, null, null, null, null);
        assertThat(service.resolveNovelIds(request)).containsExactly(4L, 6L);
    }

    @Test
    @DisplayName("filter 模式应委托小说画廊查询并应用排除列表")
    void shouldResolveFilterMode() {
        NovelBatchRequest.Filter filter = new NovelBatchRequest.Filter(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        NovelBatchRequest request = new NovelBatchRequest("filter",
                null, List.of(8L), filter, null, null, null, null, null);
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
        assertThatThrownBy(() -> service.exportNovels(List.of(1L), "author", "7z", false, null))
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
    void shouldSkipNovelWhoseOwnedDetailsDisappear() throws Exception {
        WorkMetadata meta = new WorkMetadata(7L, "Story", null, 0, false,
                88L, "Writer", null, null, null, List.of(), 0L, 1, "txt", "/n/7",
                false, null, null, null, null, true);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L))).thenReturn(List.of(meta));
        when(novelWorkDetailsRepository.findAll(List.of(7L))).thenReturn(Map.of());

        ArchiveExportResult result =
                service.exportNovels(List.of(7L), "author", "zip", false, null);

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
                false, null, null, null, null, null);
        WorkMetadata missingDetails = new WorkMetadata(8L, "Missing", null, 0, false,
                89L, "Writer 2", null, null, null, List.of(), 0L, 1, "txt", "/n/8",
                false, null, null, null, null, null);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L, 8L)))
                .thenReturn(List.of(included, missingDetails));
        when(novelWorkDetailsRepository.findAll(List.of(7L, 8L))).thenReturn(Map.of(
                7L, new NovelWorkDetails(7L, 100, 200, 60, null, null, null,
                        List.of(), List.of())));
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, folder, 1,
                        List.of(new WorkAssetFile(0, content, "txt")))));
        ArchiveExportResult result =
                service.exportNovels(List.of(7L, 8L), "author", "zip", true, null);

        assertThat(result.archiveToken()).isEqualTo("token-2");
        ArgumentCaptor<ArchiveExportRequest> requestCaptor =
                ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(requestCaptor.capture());
        assertThat(requestCaptor.getValue().deleteAfterReady())
                .isEqualTo(new ArchiveWorkDeletion(WorkType.NOVEL.name(), List.of(7L)));
    }

    @Test
    @DisplayName("导出选项只包含全部所选小说共有的精确译文")
    void commonVariants() {
        when(novelWorkDetailsRepository.findAll(List.of(7L, 8L))).thenReturn(Map.of(
                7L, details(7L, List.of("zh-CN@youdao", "zh-CN@ai", "en")),
                8L, details(8L, List.of("zh-CN@ai", "en"))));
        assertThat(service.exportOptions(List.of(7L, 8L)).variants()).containsExactly("zh-CN@ai", "en");
        when(novelWorkDetailsRepository.findAll(List.of(7L, 8L))).thenReturn(Map.of(
                7L, details(7L, List.of("zh-CN@ai"))));
        assertThat(service.exportOptions(List.of(7L, 8L)).variants()).isEmpty();
    }

    @Test
    @DisplayName("译文复用归档目录和格式，保留源文件且不混入其它引擎")
    void translatedArchive() throws Exception {
        Path original = translatedFixture();
        var rendered = new top.sywyar.pixivdownload.novel.export.NovelExportService.Export(
                "translated.txt", "youdao text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(novelExportService.export(7L,
                top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat.TXT, "zh-CN@youdao"))
                .thenReturn(rendered);
        var result = service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@youdao");
        assertThat(result.archiveToken()).isEqualTo("token-2");
        var captured = ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(captured.capture());
        var archive = captured.getValue();
        assertThat(archive.deleteAfterReady()).isNull();
        assertThat(archive.entries()).extracting(ArchiveExportEntry::entryName)
                .containsExactly("7/translated.txt", "manifest.json");
        assertThat(new String(archive.entries().getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("youdao text");
        assertThat(new String(archive.entries().getLast().bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("zh-CN@youdao");
        assertThat(Files.readString(original)).isEqualTo("original text");
    }

    @Test
    @DisplayName("译文 HTML 归档清单包含有效插图引用及同目录图片")
    void translatedHtmlArchiveContainsReferencedImage() throws Exception {
        translatedFixture();
        Path html = Files.writeString(tempDir.resolve("content.html"), "Original HTML");
        Path image = Files.write(tempDir.resolve("embed_7.png"), new byte[]{1, 2, 3});
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, tempDir, 1, List.of(
                        new WorkAssetFile(0, html, "html"), new WorkAssetFile(1, image, "png")))));
        var novels = org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.db.NovelDatabase.class);
        var novel = org.mockito.Mockito.mock(top.sywyar.pixivdownload.novel.db.NovelRecord.class);
        when(novels.getNovel(7)).thenReturn(novel);
        when(novel.rawContent()).thenReturn("Original body[uploadedimage:7]");
        when(novel.title()).thenReturn("Story");
        when(novel.folder()).thenReturn(tempDir.toString());
        when(novels.hasTranslation(7, "zh-CN@youdao")).thenReturn(true);
        when(novels.getTranslationContent(7, "zh-CN@youdao")).thenReturn("Translated body[uploadedimage:7]");
        when(novels.getNovelImageIds(7)).thenReturn(List.of("7"));
        when(novels.getNovelImageExt(7, "7")).thenReturn("png");
        var renderer = new top.sywyar.pixivdownload.novel.export.NovelExportService(novels,
                workMetadataRepository, workAssetService,
                top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages.messageResolver());
        service = new NovelBatchService(novelGalleryService, workMetadataRepository, novelWorkDetailsRepository,
                workAssetService, workCollectionMembership, archiveExportService, JsonMapper.builderWithJackson2Defaults().build(), renderer);

        service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@youdao");
        var captured = ArgumentCaptor.forClass(ArchiveExportRequest.class);
        verify(archiveExportService).export(captured.capture());
        var entries = captured.getValue().entries();
        var document = entries.stream().filter(e -> e.entryName().endsWith(".html")).findFirst().orElseThrow();
        assertThat(new String(document.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Translated body", "<img", "src=\"embed_7.png\"").doesNotContain("Original body");
        var picture = entries.stream().filter(e -> e.entryName().equals("7/embed_7.png")).findFirst().orElseThrow();
        assertThat(picture.sourcePath()).isEqualTo(image);
        assertThat(Files.readAllBytes(picture.sourcePath())).containsExactly(1, 2, 3);
        assertThat(Files.readString(html)).isEqualTo("Original HTML");
        assertThat(captured.getValue().deleteAfterReady()).isNull();
    }

    @Test
    @DisplayName("译文缺失、强制删除及生成字节超限均不提交归档")
    void rejectUnsafeOrUnavailableTranslation() throws Exception {
        translatedFixture();
        assertThatThrownBy(() -> service.exportNovels(List.of(7L), "id", "zip", true, "zh-CN@youdao"))
                .isInstanceOf(NovelBatchService.ExportRejected.class).hasMessage("invalid-request");
        assertThatThrownBy(() -> service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@ai"))
                .isInstanceOf(NovelBatchService.ExportRejected.class).hasMessage("unavailable");
        var format = top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat.TXT;
        when(novelExportService.export(7L, format, "zh-CN@youdao")).thenReturn(null);
        assertThatThrownBy(() -> service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@youdao"))
                .hasMessage("unavailable");
        when(novelExportService.export(7L, format, "zh-CN@youdao")).thenReturn(
                new top.sywyar.pixivdownload.novel.export.NovelExportService.Export("large.txt",
                        new byte[(int) NovelBatchService.MAX_GENERATED_BYTES + 1]));
        assertThatThrownBy(() -> service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@youdao"))
                .hasMessage("too-large");
        verify(archiveExportService, org.mockito.Mockito.never()).export(any());
    }

    @Test
    @DisplayName("多个文档共用累计生成预算，未超单文件限制也不能超总量")
    void generatedBudgetIsCumulative() throws Exception {
        Path original = translatedFixture();
        Path html = Files.writeString(tempDir.resolve("content.html"), "original html");
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, tempDir, 1, List.of(
                        new WorkAssetFile(0, original, "txt"), new WorkAssetFile(0, html, "html")))));
        byte[] halfBudget = new byte[(int) (NovelBatchService.MAX_GENERATED_BYTES / 2) + 1];
        when(novelExportService.export(org.mockito.ArgumentMatchers.eq(7L),
                any(top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat.class),
                org.mockito.ArgumentMatchers.eq("zh-CN@youdao"))).thenReturn(
                new top.sywyar.pixivdownload.novel.export.NovelExportService.Export("translated.txt", halfBudget));
        assertThatThrownBy(() -> service.exportNovels(List.of(7L), "id", "zip", false, "zh-CN@youdao"))
                .hasMessage("too-large");
        verify(archiveExportService, org.mockito.Mockito.never()).export(any());
    }

    private Path translatedFixture() throws Exception {
        Path original = Files.writeString(tempDir.resolve("content.txt"), "original text");
        WorkMetadata meta = new WorkMetadata(7L, "Story", null, 0, false,
                88L, "Writer", null, null, null, List.of(), 0L, 1, "txt", tempDir.toString(),
                false, null, null, null, null, null);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L))).thenReturn(List.of(meta));
        when(novelWorkDetailsRepository.findAll(List.of(7L)))
                .thenReturn(Map.of(7L, details(7L, List.of("zh-CN@youdao"))));
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, tempDir, 1,
                        List.of(new WorkAssetFile(0, original, "txt")))));
        return original;
    }

    private static NovelWorkDetails details(long id, List<String> variants) {
        return new NovelWorkDetails(id, 100, 200, 60, 1, "ja", null, List.of(), variants);
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
                false, null, null, null, null, null);
        when(workMetadataRepository.findAll(WorkType.NOVEL, List.of(7L))).thenReturn(List.of(meta));
        when(novelWorkDetailsRepository.findAll(List.of(7L))).thenReturn(Map.of(
                7L, new NovelWorkDetails(7L, 100, 200, 60, null, null, null,
                        List.of(), List.of())));
        when(workAssetService.findAsset(WorkType.NOVEL, 7L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.NOVEL, 7L, folder, 1,
                        List.of(new WorkAssetFile(0, content, "txt")))));
        ArchiveExportResult result =
                service.exportNovels(List.of(7L), groupBy, "zip", deleteAfter, null);

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
