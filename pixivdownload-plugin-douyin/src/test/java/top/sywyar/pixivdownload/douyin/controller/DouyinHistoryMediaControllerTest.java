package top.sywyar.pixivdownload.douyin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Douyin 历史媒体读取端点")
class DouyinHistoryMediaControllerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("只读取作品目录内由历史记录定位的媒体文件")
    void servesOnlyRecordedMediaInsideWorkFolder() throws Exception {
        Path media = tempDir.resolve("video.mp4");
        Files.write(media, new byte[]{1, 2, 3});
        DouyinHistoryService service = mock(DouyinHistoryService.class);
        when(service.findById("7351")).thenReturn(Optional.of(work("7351", tempDir)));
        when(service.findFilesByWorkId("7351")).thenReturn(List.of(
                file("7351", 0, "video.mp4"), file("7351", 1, "../outside.mp4")));
        DouyinHistoryMediaController controller = new DouyinHistoryMediaController(service);

        assertThat(controller.media("7351", 0).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.media("7351", 0).getBody()).isNotNull();
        assertThat(controller.media("7351", 1).getStatusCode().value()).isEqualTo(404);
    }

    private static DouyinWorkFileRecord file(String workId, int index, String fileName) {
        return new DouyinWorkFileRecord(workId, index, null, "VIDEO", fileName,
                "mp4", 3L, "video/mp4", 1000L);
    }

    private static DouyinWorkRecord work(String id, Path folder) {
        return new DouyinWorkRecord(id, id, folder.toString(), 1, "mp4", 1000L, false,
                "VIDEO", null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
