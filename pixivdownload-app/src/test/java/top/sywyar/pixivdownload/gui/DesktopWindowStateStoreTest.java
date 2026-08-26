package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面窗口状态持久化")
class DesktopWindowStateStoreTest {
    @TempDir Path directory;

    @Test
    @DisplayName("保存尺寸和最大化状态并忽略损坏文件")
    void roundTripsValidStateAndIgnoresInvalidState() throws Exception {
        DesktopWindowStateStore store = new DesktopWindowStateStore(directory);
        WindowStateSnapshot expected = new WindowStateSnapshot(1120, 760, true);

        assertThat(store.save(expected)).isTrue();
        assertThat(store.load()).contains(expected);

        Files.writeString(directory.resolve("window-state.json"),
                "{\"width\":0,\"height\":760,\"maximized\":true}", StandardCharsets.UTF_8);
        assertThat(store.load()).isEmpty();
    }
}
