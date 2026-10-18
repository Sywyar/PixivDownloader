package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面引导状态持久化")
class DesktopUiOnboardingStateTest {
    @TempDir Path directory;
    private String previousStateDirectory;

    @BeforeEach
    void useIsolatedState() {
        previousStateDirectory = System.getProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, directory.resolve("state").toString());
    }

    @AfterEach
    void restoreStateDirectory() {
        if (previousStateDirectory == null) System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        else System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, previousStateDirectory);
    }

    @Test
    @DisplayName("前进和返回均保存当前进度，重新读取恢复最后一步")
    void savesEveryStepAcrossInstances() {
        var state = new DesktopUiOnboardingState();
        assertThat(state.snapshot(null).progress()).isZero();
        for (int step : new int[]{1, 5, 2, 0}) {
            assertThat(state.saveProgress(step)).isTrue();
            assertThat(new DesktopUiOnboardingState().snapshot(null).progress()).isEqualTo(step);
        }
    }

    @Test
    @DisplayName("损坏的进度回退默认值且下次保存能够修复")
    void replacesMalformedProgress() throws Exception {
        var file = RuntimeFiles.guiStateDirectory().resolve("wizard-progress");
        Files.writeString(file, "invalid", StandardCharsets.UTF_8);
        var state = new DesktopUiOnboardingState();
        assertThat(state.snapshot(null).progress()).isZero();
        assertThat(state.saveProgress(3)).isTrue();
        assertThat(new DesktopUiOnboardingState().snapshot(null).progress()).isEqualTo(3);
    }

    @Test
    @DisplayName("进度写入失败返回失败并保留阻塞路径的内容")
    void rejectsFailedProgressWrite() throws Exception {
        Path blocker = Files.createDirectories(RuntimeFiles.guiStateDirectory().resolve("wizard-progress"));
        Path existing = blocker.resolve("keep.txt");
        Files.writeString(existing, "keep", StandardCharsets.UTF_8);
        assertThat(new DesktopUiOnboardingState().saveProgress(3)).isFalse();
        assertThat(Files.readString(existing, StandardCharsets.UTF_8)).isEqualTo("keep");
    }

    @Test
    @DisplayName("一次性标记独立保留，清除引导状态不删除其它桌面数据")
    void preservesMarkersAndClearsOnlyOnboardingState() throws Exception {
        var state = new DesktopUiOnboardingState();
        assertThat(state.markSeen()).isTrue();
        assertThat(state.markProxyConfigured()).isTrue();
        assertThat(state.markFinished()).isTrue();
        Path gui = RuntimeFiles.guiStateDirectory();
        Path seen = gui.resolve("onboarding-seen");
        Files.writeString(seen, "legacy marker", StandardCharsets.UTF_8);
        assertThat(state.markSeen()).isTrue();
        assertThat(Files.readString(seen, StandardCharsets.UTF_8)).isEqualTo("legacy marker");
        assertThat(state.saveProgress(4)).isTrue();
        var snapshot = new DesktopUiOnboardingState().snapshot(null);
        assertThat(snapshot.seen()).isTrue();
        assertThat(snapshot.proxyConfigured()).isTrue();
        assertThat(snapshot.finished()).isTrue();
        assertThat(snapshot.complete()).isFalse();
        Path unrelated = gui.resolve("window-state.json");
        Files.writeString(unrelated, "{}", StandardCharsets.UTF_8);
        assertThat(state.clear()).isTrue();
        assertThat(state.snapshot(null).seen()).isFalse();
        assertThat(state.snapshot(null).proxyConfigured()).isFalse();
        assertThat(state.snapshot(null).finished()).isFalse();
        assertThat(state.snapshot(null).progress()).isZero();
        assertThat(Files.readString(unrelated, StandardCharsets.UTF_8)).isEqualTo("{}");
    }
}
