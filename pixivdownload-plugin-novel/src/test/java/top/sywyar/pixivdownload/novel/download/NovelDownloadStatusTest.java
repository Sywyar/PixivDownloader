package top.sywyar.pixivdownload.novel.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说下载状态发布")
class NovelDownloadStatusTest {
    @Test
    @DisplayName("失败一次发布终态和错误并保留此前快照")
    void failurePublishesCompleteState() {
        var status = new NovelDownloadStatus(7L, "title", "txt", "owner");
        status.setStage("saving");
        var active = status.getState();
        status.fail("write failed");
        assertThat(active).isEqualTo(new NovelDownloadStatus.State("saving", false, false, false, null));
        assertThat(status.getState()).isEqualTo(
                new NovelDownloadStatus.State("saving", true, true, false, "write failed"));
    }

    @Test
    @DisplayName("取消后迟到进度成功和失败均不覆盖取消终态")
    void cancellationKeepsItsTerminalState() {
        var status = new NovelDownloadStatus(7L, "title", "txt", "owner");
        status.cancel("cancelled");
        status.setStage("saving");
        status.complete();
        status.fail("late failure");
        assertThat(status.getState()).isEqualTo(
                new NovelDownloadStatus.State("cancelled", true, false, true, "cancelled"));
    }

    @Test
    @DisplayName("成功发布保留后处理失败与用户取消的原有语义")
    void completedWorkCanReportPostProcessingFailureOrCancellation() {
        var status = new NovelDownloadStatus(7L, "title", "txt", "owner");
        status.complete();
        assertThat(status.getState()).isEqualTo(
                new NovelDownloadStatus.State("completed", true, false, false, null));
        status.fail("database unavailable");
        assertThat(status.isCompleted()).isTrue();
        assertThat(status.isFailed()).isTrue();
        status.cancel("cancelled");
        assertThat(status.isCancelled()).isTrue();
        assertThat(status.isFailed()).isFalse();
    }
}
