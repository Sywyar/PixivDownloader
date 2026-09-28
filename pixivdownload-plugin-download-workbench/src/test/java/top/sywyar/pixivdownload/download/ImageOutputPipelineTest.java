package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ImageOutputPipelineTest {
    @Test
    @org.junit.jupiter.api.Timeout(10)
    @DisplayName("后提交的图片先完成时释放容量，不被前一张慢图阻塞")
    void completedImageReleasesCapacityOutOfOrder() throws Exception {
        var releaseFirst = new CountDownLatch(1);
        var third = new CountDownLatch(1);
        try (var pipeline = new ImageOutputPipeline(true, () -> false)) {
            pipeline.submit(() -> {
                try { releaseFirst.await(); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            });
            pipeline.submit(() -> {});
            pipeline.awaitCapacity();
            pipeline.submit(third::countDown);
            assertThat(third.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseFirst.getCount()).isEqualTo(1);
            releaseFirst.countDown();
            pipeline.finish();
        } finally {
            releaseFirst.countDown();
        }
    }
}
