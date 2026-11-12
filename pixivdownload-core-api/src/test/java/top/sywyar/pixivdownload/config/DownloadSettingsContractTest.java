package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载设置核心契约")
class DownloadSettingsContractTest {

    @Test
    @DisplayName("只暴露固定前缀、按模式选择的最终目录模板与通用并发")
    void exposesOnlySharedHostDownloadSettings() {
        Method[] methods = DownloadSettings.class.getDeclaredMethods();

        assertThat(DownloadSettings.class.isInterface()).isTrue();
        assertThat(Arrays.stream(methods).filter(method -> method.getName().equals("directoryTemplate")).findFirst().orElseThrow()
                .getParameterTypes()).containsExactly(String.class);
        assertThat(Arrays.stream(methods).collect(Collectors.toMap(Method::getName, Method::getReturnType)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "getRootFolder", String.class,
                        "directoryTemplate", String.class,
                        "directoryOverrides", Map.class,
                        "getMaxConcurrent", int.class));
    }
}
