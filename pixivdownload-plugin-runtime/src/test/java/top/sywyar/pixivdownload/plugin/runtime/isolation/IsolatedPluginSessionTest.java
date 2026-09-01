package top.sywyar.pixivdownload.plugin.runtime.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("隔离插件 worker 会话")
class IsolatedPluginSessionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("worker 保留宿主环境并仅覆盖私有临时目录")
    void preservesInheritedEnvironment() {
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "tools-path");
        environment.put("HTTP_PROXY", "http://proxy.example:8080");
        environment.put("USERPROFILE", "user-profile");
        environment.put("LANG", "zh_CN.UTF-8");

        IsolatedPluginSession.configureEnvironment(builder, tempDir);

        assertThat(environment).containsEntry("PATH", "tools-path")
                .containsEntry("HTTP_PROXY", "http://proxy.example:8080")
                .containsEntry("USERPROFILE", "user-profile")
                .containsEntry("LANG", "zh_CN.UTF-8")
                .containsEntry("TEMP", tempDir.toString())
                .containsEntry("TMP", tempDir.toString())
                .containsEntry("TMPDIR", tempDir.toString());
    }
}
