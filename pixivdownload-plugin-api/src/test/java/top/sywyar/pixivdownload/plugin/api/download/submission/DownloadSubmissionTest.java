package top.sywyar.pixivdownload.plugin.api.download.submission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DisplayName("公开下载命令输入边界")
class DownloadSubmissionTest {
    @Test @DisplayName("按 UTF-8 累计限制选项，拒绝多余项且隔离原始可变 map")
    void validatesAndCopiesOptions() {
        var values = new HashMap<>(Map.of("format", "txt"));
        var command = new DownloadSubmission(UUID.randomUUID(), "example", "opaque:一", values);
        values.put("format", "html");
        assertThat(command.options()).containsEntry("format", "txt");
        assertThatThrownBy(() -> command.options().put("format", "epub"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(DownloadSubmission.copyOptions(Map.of("x", "a".repeat(DownloadSubmission.MAX_OPTIONS_BYTES - 1))))
                .hasSize(1);
        assertThatThrownBy(() -> DownloadSubmission.copyOptions(Map.of("x", "a".repeat(DownloadSubmission.MAX_OPTIONS_BYTES))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DownloadSubmission.copyOptions(Map.of("x", "作".repeat(6000))))
                .isInstanceOf(IllegalArgumentException.class);
        var many = new HashMap<String, String>();
        for (int i = 0; i < DownloadSubmission.MAX_OPTIONS; i++) many.put("key" + i, "value");
        assertThat(DownloadSubmission.copyOptions(many)).hasSize(DownloadSubmission.MAX_OPTIONS);
        many.put("extra", "value");
        assertThatThrownBy(() -> DownloadSubmission.copyOptions(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DownloadSubmission.copyOptions(Map.of("../path", "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
