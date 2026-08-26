package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Compose 工具表单默认值")
class DesktopToolsControllerTest {
    @Test
    @DisplayName("界面显示的路径默认值同时写入动作读取的表单状态")
    void storesVisiblePathDefaultsInFormState() {
        Path database = Path.of("data", "pixiv-download.db");
        Map<String, String> values = new HashMap<>();
        values.put("tools.migration.db", "custom.db");

        DesktopToolsController controller = new DesktopToolsController(
                null,
                host(database),
                "downloads",
                values
        );

        assertEquals(database.toString(), controller.form("tools.folder.db", ""));
        assertEquals("custom.db", controller.form("tools.migration.db", ""));
        assertEquals("downloads", controller.form("tools.migration.root", ""));
    }

    private static DesktopUiHost host(Path database) {
        return (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "resolveDatabasePath" -> database;
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions(
                            database.toString(),
                            "localhost",
                            8080,
                            false,
                            1000,
                            0,
                            false
                    );
                    case "loadImageClassifierSettings" -> new DesktopUiHost.ImageClassifierSettings(
                            "",
                            false,
                            "http://localhost:6999",
                            List.of()
                    );
                    default -> throw new AssertionError("unexpected DesktopUiHost call: " + method.getName());
                }
        );
    }
}
