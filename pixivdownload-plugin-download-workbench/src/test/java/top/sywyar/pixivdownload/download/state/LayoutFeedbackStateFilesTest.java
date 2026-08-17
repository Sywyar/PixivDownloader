package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("布局调查状态文件归属")
class LayoutFeedbackStateFilesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("布局调查状态使用下载工作台自有 state 目录")
    void resolvesStateFileUnderOwnerDirectory() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        Path ownerDirectory = tempDir.resolve("state/download-workbench");
        when(runtimePathProvider.stateDirectory())
                .thenReturn(ownerDirectory);

        LayoutFeedbackStateFiles files = new LayoutFeedbackStateFiles(runtimePathProvider);

        assertThat(files.stateFile()).isEqualTo(ownerDirectory.resolve("layout-feedback-state.json"));
        verify(runtimePathProvider).stateDirectory();
    }
}
