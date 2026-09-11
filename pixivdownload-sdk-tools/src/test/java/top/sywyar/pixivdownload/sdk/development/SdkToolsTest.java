package top.sywyar.pixivdownload.sdk.development;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SDK 停止归属与共用语言资源")
class SdkToolsTest {
    @TempDir
    Path temp;

    @Test
    @DisplayName("停止只在当前工程已标记的运行副本写入请求")
    void stopCannotEscapeCurrentProjectRun() throws Exception {
        Path project = Files.createDirectories(temp.resolve("中文 project"));
        String id = "0123456789abcdef";
        Path run = Files.createDirectories(project.resolve(".dev/runs").resolve(id));
        Path current = project.resolve(".dev/current-run.json");
        SdkRuntimeLock.JSON.writeValue(current.toFile(), Map.of("run", id));
        assertThatThrownBy(() -> SdkTools.stop(project))
                .isInstanceOf(IOException.class).hasMessageContaining("SESSION_ROOT");
        Files.writeString(run.resolve(".sdk-run"), "owned", StandardCharsets.UTF_8);
        assertThat(SdkTools.execute(new String[]{"stop", project.toString()})).isZero();
        assertThat(run.resolve("stop.request")).exists().isEmptyFile();
        SdkRuntimeLock.JSON.writeValue(current.toFile(), Map.of("run", "../unrelated"));
        assertThatThrownBy(() -> SdkTools.stop(project))
                .isInstanceOf(IOException.class).hasMessageContaining("SESSION_ID");
        assertThat(project.resolve(".dev/stop.request")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US", "zh-Hant", "ja-JP", "ko-KR"})
    @DisplayName("支持的语言均能解析工具文案并保留端口与错误参数")
    void toolMessagesUseSharedLocaleCatalog(String tag) {
        Locale locale = Locale.forLanguageTag(tag);
        assertThat(MessageBundles.get(locale, "sdk.error", "SDK_TEST_ERROR"))
                .contains("SDK_TEST_ERROR").doesNotContain("sdk.error");
        assertThat(MessageBundles.get(locale, "sdk.debug", "current-plugin", "declarative-process", "5005"))
                .contains("current-plugin", "declarative-process", "127.0.0.1:5005").doesNotContain("sdk.debug");
        assertThat(MessageBundles.get(locale, "sdk.usage")).doesNotContain("sdk.usage");
    }
}
