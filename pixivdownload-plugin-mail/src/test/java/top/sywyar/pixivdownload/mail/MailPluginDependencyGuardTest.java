package top.sywyar.pixivdownload.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("mail 插件描述符依赖")
class MailPluginDependencyGuardTest {

    @Test
    @DisplayName("mail 依赖 notification 基础插件")
    void mailDependsOnNotificationPlugin() throws IOException {
        Properties properties = new Properties();
        try (var in = MailPluginDependencyGuardTest.class.getResourceAsStream("/plugin.properties")) {
            assertThat(in).as("plugin.properties 应存在于插件根部").isNotNull();
            properties.load(in);
        }

        assertThat(properties.getProperty("plugin.dependencies"))
                .isEqualTo("notification@1.0");
    }
}
