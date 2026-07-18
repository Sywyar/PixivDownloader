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
    @DisplayName("只暴露跨插件共享的下载根目录、扁平布局与通用并发")
    void exposesOnlySharedHostDownloadSettings() {
        Method[] methods = DownloadSettings.class.getDeclaredMethods();

        assertThat(DownloadSettings.class.isInterface()).isTrue();
        assertThat(methods).allSatisfy(method -> assertThat(method.getParameterCount()).isZero());
        assertThat(Arrays.stream(methods).collect(Collectors.toMap(Method::getName, Method::getReturnType)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "getRootFolder", String.class,
                        "isUserFlatFolder", boolean.class,
                        "getMaxConcurrent", int.class));
    }
}
