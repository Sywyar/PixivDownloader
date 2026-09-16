package top.sywyar.pixivdownload.core.work.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.*;

@DisplayName("下载路径授权与文件系统限制")
class DownloadPathPlanTest {
    private final Path directory = Path.of("target", "path-planning");
    private final IntFunction<List<String>> names = length -> PixivWorkFileNameFormatter.formatAll(
            "{artwork_title}", 123, "作品😀".repeat(60), 7L, "author", 1, 3, false, 0, length);
    private final List<String> defaults = List.of("123_p0", "123_p1", "123_p2");
    private final List<String> suffixes = List.of(".jpg", ".image-download.part");

    private DownloadPathPlan resolve(DownloadPathLimits limits, DownloadPathAction action) {
        return DownloadPathPlan.resolve(directory, limits, names, defaults, suffixes, List.of(), action);
    }

    @Test
    @DisplayName("询问不授权改名且候选包含临时文件预算")
    void askDoesNotRename() {
        DownloadPathLimits limits = new DownloadPathLimits(255, 0, true);
        assertThatThrownBy(() -> resolve(limits, DownloadPathAction.ASK))
                .isInstanceOfSatisfying(DownloadPathPlan.NeedsAction.class, failure -> {
                    assertThat(failure.problem().truncatedPath()).isNotNull();
                    assertThat(failure.problem().defaultPath()).endsWith("123_p0.jpg");
                });
        DownloadPathPlan result = resolve(limits, DownloadPathAction.TRUNCATE);
        assertThat(result.maxLength()).isLessThan(180);
        assertThat(result.baseNames()).doesNotHaveDuplicates();
        for (String name : result.baseNames()) {
            assertThat(limits.accepts(directory.resolve(name + ".image-download.part"))).isTrue();
            assertThat(Character.isHighSurrogate(name.charAt(name.length() - 1))).isFalse();
        }
        assertThat(resolve(limits, DownloadPathAction.DEFAULT_NAME).baseNames()).isEqualTo(defaults);
        assertThatThrownBy(() -> resolve(limits, DownloadPathAction.CANCEL)).isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("未知上限和支持长路径的环境保持原名，默认取消仅在超限时执行")
    void supportedPathsRemainUnchanged() {
        assertThat(resolve(DownloadPathLimits.UNKNOWN, DownloadPathAction.CANCEL).maxLength()).isEqualTo(180);
        Path longDirectory = directory.resolve("a".repeat(150)).resolve("b".repeat(150));
        DownloadPathLimits windows = new DownloadPathLimits(255, 32001, false);
        assertThat(windows.accepts(longDirectory.resolve("123_p0.jpg"))).isTrue();
    }

    @Test
    @DisplayName("目录本身过长时不得声称短文件名可以解决")
    void directoryOverflowRequiresUserAction() {
        assertThatThrownBy(() -> resolve(new DownloadPathLimits(4, 0, true), DownloadPathAction.DEFAULT_NAME))
                .isInstanceOfSatisfying(DownloadPathPlan.NeedsAction.class, failure -> {
                    assertThat(failure.problem().truncatedPath()).isNull();
                    assertThat(failure.problem().defaultPath()).isNull();
                });
        assertThat(DownloadPathAction.parse(null)).isEqualTo(DownloadPathAction.ASK);
        assertThat(DownloadPathAction.parse("unknown")).isEqualTo(DownloadPathAction.ASK);
    }
}
