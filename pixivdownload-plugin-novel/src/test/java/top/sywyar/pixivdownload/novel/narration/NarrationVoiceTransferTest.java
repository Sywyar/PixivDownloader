package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("参考音格式与归档声明一致性")
class NarrationVoiceTransferTest {
    @Test @DisplayName("存储层管理的所有参考音扩展名均被迁移声明覆盖")
    void declarationCoversEveryExtensionManagedByTheStore() throws Exception {
        var paths = mock(NarrationReferenceVoicePaths.class);
        when(paths.file(anyLong(), anyInt(), anyString())).thenAnswer(call ->
                Path.of(call.getArgument(0).toString(), call.getArgument(1) + "." + call.getArgument(2)));
        var files = mock(FileTransactionService.class);
        var definition = new NarrationVoiceTransfer().definition();
        var pattern = Pattern.compile(definition.pattern());
        doAnswer(call -> {
            List<Path> managed = call.getArgument(1);
            assertThat(managed).isNotEmpty().allSatisfy(path ->
                    assertThat(pattern.matcher(path.toString().replace('\\', '/')).matches()).as(path.toString()).isTrue());
            return null;
        }).when(files).changeFiles(anyMap(), anyList(), any());
        new NarrationReferenceVoiceStore(paths, mock(DatabaseAvailability.class), files)
                .deleteCharacterFiles(7, 0, () -> {});
        verify(files).changeFiles(anyMap(), anyList(), any());
        assertThat(definition.requiresDatabase()).isTrue();
    }
}
