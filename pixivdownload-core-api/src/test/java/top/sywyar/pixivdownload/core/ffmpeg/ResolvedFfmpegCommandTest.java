package top.sywyar.pixivdownload.core.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("FFmpeg 命令解析结果")
class ResolvedFfmpegCommandTest {

    @Test
    @DisplayName("命令和来源不得为空")
    void commandAndSourceMustBePresent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResolvedFfmpegCommand(null, ResolvedFfmpegCommand.Source.SYSTEM));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ResolvedFfmpegCommand("  ", ResolvedFfmpegCommand.Source.SYSTEM));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResolvedFfmpegCommand("ffmpeg", null));
    }
}
