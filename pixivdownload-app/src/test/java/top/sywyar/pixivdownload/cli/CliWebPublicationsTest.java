package top.sywyar.pixivdownload.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("本机发布恢复 CLI 输入与单实例命令分类")
class CliWebPublicationsTest {
    @Test @DisplayName("恢复命令必须参与单实例检查，冲突和不完整选择不能访问发布状态")
    void requiresExclusiveCommandAndExactSelection() {
        var store = mock(WebPublicationStore.class);
        var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThat(CliSetupArguments.containsCommand(new String[]{"--web-publications"})).isTrue();
        assertThat(CliSetupArguments.inspect(new String[]{"--web-publications"}).unknownPositions()).isEmpty();
        assertThat(CliWebPublications.run(new String[]{"--web-publications", "--setup"}, store, output)).isEqualTo(2);
        assertThat(CliWebPublications.run(new String[]{"--web-publications", "--publication-restore=missing"}, store, output)).isEqualTo(2);
        assertThat(CliWebPublications.run(new String[]{"--web-publications", "--publication-sha256=missing"}, store, output)).isEqualTo(2);
        verifyNoInteractions(store);
    }
}
