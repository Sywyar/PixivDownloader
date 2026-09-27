package top.sywyar.pixivdownload.guicompose.model.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopUiIdentifierTest {
    @Test
    @DisplayName("节点与页面标识及物理键保留字符和长度边界")
    void identifiersKeepTheirValidationBoundaries() {
        var title = DesktopUiNode.TextToken.raw("Title");
        for (String id : Arrays.asList("a", "0", "Config.item_2:action-3", "a".repeat(128))) {
            var node = new DesktopUiNode.Text(id, title, DesktopUiNode.TextStyle.BODY, false, false);
            assertEquals(id, node.id());
            assertEquals(id, new DesktopUiDocument.Page(id, title, node).id());
            assertEquals(id, DesktopUiNode.TextToken.key(id).key());
        }
        var content = new DesktopUiNode.Text("content", title, DesktopUiNode.TextStyle.BODY, false, false);
        for (String id : Arrays.asList(null, "", "_a", "a/b", "a b", "a\n", "汉字", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DesktopUiNode.Text(id, title, DesktopUiNode.TextStyle.BODY, false, false));
            assertThrows(IllegalArgumentException.class, () -> new DesktopUiDocument.Page(id, title, content));
            assertThrows(IllegalArgumentException.class, () -> DesktopUiDocument.TrayItem.separator(id));
        }
        for (String key : Arrays.asList("A", "ArrowUp", "Key1", "A".repeat(32))) {
            assertEquals(key, DesktopUiDocument.KeyStroke.key(key).key());
        }
        for (String key : Arrays.asList(null, "", "1", "Key_A", "Key A", "A\n", "A".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> DesktopUiDocument.KeyStroke.key(key));
        }
    }
}
