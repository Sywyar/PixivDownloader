package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("push 插件描述符依赖")
class PushPluginDependencyGuardTest {

    @Test
    @DisplayName("push 依赖 notification 基础插件")
    void pushDependsOnNotificationPlugin() throws IOException {
        Properties properties = new Properties();
        try (var in = PushPluginDependencyGuardTest.class.getResourceAsStream("/plugin.properties")) {
            assertThat(in).as("plugin.properties 应存在于插件根部").isNotNull();
            properties.load(in);
        }

        assertThat(properties.getProperty("plugin.dependencies"))
                .isEqualTo("notification@1.0");
    }
}
