package top.sywyar.pixivdownload.plugin.api.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("数据库连接字段声明")
class DatabaseConnectionFieldTest {
    @Test
    @DisplayName("凭据只能声明为密码类型，不能声明其它宿主配置键")
    void restrictsBindingsAndSecretTypes() {
        assertDoesNotThrow(() -> new DatabaseConnectionField("database.password", GuiConfigFieldType.PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionField("database.password", GuiConfigFieldType.STRING));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionField("server.port", GuiConfigFieldType.INT));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionField(null, GuiConfigFieldType.STRING));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionField("database.jdbc-url", null));
    }
}
