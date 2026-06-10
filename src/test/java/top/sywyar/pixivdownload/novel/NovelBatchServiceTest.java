package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.request.NovelBatchRequest;
import top.sywyar.pixivdownload.quota.ArchiveExportSupport;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.quota.UserQuotaService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
@DisplayName("NovelBatchService 单元测试")
class NovelBatchServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private NovelGalleryService novelGalleryService;
    @Mock
    private NovelDatabase novelDatabase;
    @Mock
    private AuthorService authorService;
    @Mock
    private CollectionService collectionService;
    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private DownloadConfig downloadConfig;

    private NovelBatchService service;

    @BeforeEach
    void setUp() {
        MultiModeConfig multiModeConfig = new MultiModeConfig();
        multiModeConfig.getQuota().setArchiveExpireMinutes(60);
        service = new NovelBatchService(novelGalleryService, novelDatabase, authorService,
                collectionService, userQuotaService, multiModeConfig, downloadConfig,
                new ObjectMapper(), TestI18nBeans.appMessages());
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
    @DisplayName("不支持的打包格式应抛出 400")
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> service.exportNovels(List.of(1L), "author", "7z", false))
                .isInstanceOf(LocalizedException.class);
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
    @DisplayName("勾选导出后删除时，打包成功回调应执行批量删除")
    void shouldDeleteAfterExportViaCallback() throws Exception {
        ArgumentCaptor<Runnable> afterReady = ArgumentCaptor.forClass(Runnable.class);
        exportSingleNovel("author", true, afterReady);
        assertThat(afterReady.getValue()).isNotNull();
        afterReady.getValue().run();
        verify(novelGalleryService).deleteNovels(List.of(7L));
    }

    private List<UserQuotaService.ArchiveItem> exportSingleNovel(String groupBy, boolean deleteAfter)
            throws Exception {
        return exportSingleNovel(groupBy, deleteAfter, ArgumentCaptor.forClass(Runnable.class));
    }

    @SuppressWarnings("unchecked")
    private List<UserQuotaService.ArchiveItem> exportSingleNovel(String groupBy, boolean deleteAfter,
                                                                 ArgumentCaptor<Runnable> afterReady)
            throws Exception {
        Path folder = tempDir.resolve("novel-7");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("content.txt"), "text");
        NovelRecord record = new NovelRecord(7L, "Story", folder.toString(), 1, "txt", 0L,
                0, false, 88L, null, null, null, null, null,
                100, 200, 60, null, null, null, null, null);
        when(novelDatabase.getNovel(7L)).thenReturn(record);
        when(novelDatabase.getNovelTags(7L)).thenReturn(List.of());
        when(authorService.getAuthorNames(any())).thenReturn(Map.of(88L, "Writer"));
        when(downloadConfig.getRootFolder()).thenReturn(tempDir.toString());
        when(userQuotaService.triggerAdminFileArchive(anyList(), anyString(), anyInt(), any()))
                .thenReturn("token-2");

        ArchiveExportSupport.ExportResult result =
                service.exportNovels(List.of(7L), groupBy, "zip", deleteAfter);

        assertThat(result.archiveToken()).isEqualTo("token-2");
        assertThat(result.fileCount()).isEqualTo(1);

        ArgumentCaptor<List<UserQuotaService.ArchiveItem>> itemsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(userQuotaService).triggerAdminFileArchive(
                itemsCaptor.capture(), eq("novels"), eq(1), afterReady.capture());
        return itemsCaptor.getValue();
    }
}
