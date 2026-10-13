package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("漫画封面传输遵守数据库失效边界")
class PixivCoverDatabaseLossTest {
    @Test @DisplayName("无库不发请求，在途失库传给调用方而不被尽力下载吞掉")
    void lossStopsTransfer() throws Exception {
        var available = new AtomicBoolean(false);
        var images = mock(PixivImageDownloader.class);
        var covers = new PixivCoverDownloader(images, available::get);
        assertThatThrownBy(() -> covers.download("https://i.pximg.net/cover.jpg", Path.of("fixture"), "cover", ""))
                .isInstanceOf(DatabaseUnavailableException.class);
        verifyNoInteractions(images);
        available.set(true);
        when(images.downloadImage(any(), any(), any(), any(), any())).thenAnswer(call -> {
            available.set(false);
            call.getArgument(4, PixivImageTransferObserver.class).checkCancelled();
            throw new AssertionError("失库后不能继续传输");
        });
        assertThatThrownBy(() -> covers.download("https://i.pximg.net/cover.jpg", Path.of("fixture"), "cover", ""))
                .isInstanceOf(DatabaseUnavailableException.class);
    }
}
