package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VisitorDownloadQuotaServiceAdapter 游客下载配额端口测试")
class VisitorDownloadQuotaServiceAdapterTest {

    private final UserQuotaService userQuotaService = mock(UserQuotaService.class);
    private final VisitorDownloadQuotaServiceAdapter adapter =
            new VisitorDownloadQuotaServiceAdapter(userQuotaService);

    @Test
    @DisplayName("应保留媒体权重预留结果的全部配额字段")
    void preservesQuotaReservationFields() {
        when(userQuotaService.checkAndReserve("visitor-1", 7))
                .thenReturn(new UserQuotaService.QuotaCheckResult(false, 3, 5, 60));

        VisitorDownloadQuotaReservation result = adapter.checkAndReserve("visitor-1", 7);

        assertThat(result).isEqualTo(new VisitorDownloadQuotaReservation(false, 3, 5, 60));
        verify(userQuotaService).checkAndReserve("visitor-1", 7);
    }

    @Test
    @DisplayName("应委托配额归档创建与完成目录登记")
    void delegatesArchiveAndDownloadedFolder() {
        Path folder = Path.of("downloads", "novel-7");
        when(userQuotaService.triggerArchive("visitor-1")).thenReturn("archive-token");

        assertThat(adapter.createArchive("visitor-1")).isEqualTo("archive-token");
        adapter.recordFolder("visitor-1", folder);

        verify(userQuotaService).triggerArchive("visitor-1");
        verify(userQuotaService).recordFolder("visitor-1", folder);
    }
}
