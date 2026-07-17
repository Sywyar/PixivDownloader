package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.archive.ArchiveExportEntry;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRequest;
import top.sywyar.pixivdownload.core.archive.ArchiveExportResult;
import top.sywyar.pixivdownload.core.archive.ArchiveWorkDeletion;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QuotaArchiveExportServiceAdapter 归档端口测试")
class QuotaArchiveExportServiceAdapterTest {

    private final UserQuotaService userQuotaService = mock(UserQuotaService.class);
    private final MultiModeSettings multiModeSettings = mock(MultiModeSettings.class);
    private final WorkDeletionService workDeletionService = mock(WorkDeletionService.class);
    private final QuotaArchiveExportServiceAdapter adapter =
            new QuotaArchiveExportServiceAdapter(
                    userQuotaService, multiModeSettings, workDeletionService);

    @Test
    @DisplayName("应以管理员 owner 委托既有 ZIP 队列并保留 manifest 与删除回调")
    void delegatesToAdminArchiveAndPreservesEntries() {
        Path image = Path.of("download", "101.png");
        byte[] manifest = "[{\"id\":101}]".getBytes(StandardCharsets.UTF_8);
        when(userQuotaService.triggerAdminFileArchive(anyList(),
                org.mockito.ArgumentMatchers.eq("artworks"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("archive-token");
        when(multiModeSettings.getArchiveExpireMinutes()).thenReturn(15);

        ArchiveExportResult result = adapter.export(new ArchiveExportRequest(
                List.of(
                        ArchiveExportEntry.file(image, "artworks/101/101.png", 101L),
                        ArchiveExportEntry.bytes("manifest.json", manifest)
                ),
                "artworks",
                1,
                1,
                "ZIP",
                new ArchiveWorkDeletion(WorkType.ARTWORK.name(), List.of(101L))
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserQuotaService.ArchiveItem>> itemsCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(userQuotaService).triggerAdminFileArchive(
                itemsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("artworks"),
                org.mockito.ArgumentMatchers.eq(1),
                completionCaptor.capture());
        List<UserQuotaService.ArchiveItem> items = itemsCaptor.getValue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path()).isEqualTo(image);
        assertThat(items.get(0).entryName()).isEqualTo("artworks/101/101.png");
        assertThat(items.get(0).workId()).isEqualTo(101L);
        assertThat(items.get(1).entryName()).isEqualTo("manifest.json");
        assertThat(items.get(1).bytes()).isEqualTo(manifest);
        assertThat(result).isEqualTo(new ArchiveExportResult("archive-token", 900, 1, 1));

        completionCaptor.getValue().run();
        verify(workDeletionService).deleteAll(WorkType.ARTWORK, List.of(101L));
    }

    @Test
    @DisplayName("不支持的格式应保留原 400 状态与 i18n 参数")
    void preservesUnsupportedFormatError() {
        ArchiveExportRequest request = new ArchiveExportRequest(
                List.of(ArchiveExportEntry.bytes("manifest.json", new byte[]{1})),
                "novels", 1, 1, " RAR ", null);

        Throwable thrown = catchThrowable(() -> adapter.export(request));

        assertThat(thrown).isInstanceOfSatisfying(LocalizedException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.getMessageCode()).isEqualTo("validation.archive.export.format.unsupported");
            assertThat(error.getMessageArgs()).containsExactly("rar");
        });
        verify(userQuotaService, never()).triggerAdminFileArchive(
                anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("没有可写文件时应返回空结果且不创建后台任务")
    void returnsEmptyWithoutSchedulingArchive() {
        ArchiveExportResult result = adapter.export(new ArchiveExportRequest(
                List.of(), "artworks", 3, 0, null, null));

        assertThat(result).isEqualTo(ArchiveExportResult.empty(3));
        verify(userQuotaService, never()).triggerAdminFileArchive(
                anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("未知完成后删除作品类型应在创建后台任务前失败")
    void rejectsUnknownDeletionWorkTypeBeforeScheduling() {
        ArchiveExportRequest request = new ArchiveExportRequest(
                List.of(ArchiveExportEntry.bytes("manifest.json", new byte[]{1})),
                "works", 1, 1, "zip",
                new ArchiveWorkDeletion("UNKNOWN", List.of(1L)));

        Throwable thrown = catchThrowable(() -> adapter.export(request));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
        verify(userQuotaService, never()).triggerAdminFileArchive(
                anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }
}
