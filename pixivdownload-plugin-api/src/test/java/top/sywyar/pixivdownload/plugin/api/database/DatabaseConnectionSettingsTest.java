package top.sywyar.pixivdownload.plugin.api.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("数据库连接设置秘密保护")
class DatabaseConnectionSettingsTest {
    @Test
    @DisplayName("字符串表示不输出地址或凭据")
    void representationDoesNotExposeCredentials() {
        var settings = new DatabaseConnectionSettings("jdbc:fixture:private", "private-user", "private-secret", Path.of("data/main.db"));
        assertThat(settings.toString()).doesNotContain("jdbc:", "private-user", "private-secret", "main.db");
        assertThat(settings.dataDirectory()).isAbsolute();
    }
}
