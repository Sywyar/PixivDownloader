package top.sywyar.pixivdownload.guicompose.model.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUiStringValuesTest {
    @Test
    @DisplayName("文本参数和表格保留不可变快照及输入限制")
    void keepsImmutableBoundedStrings() {
        List<Function<List<String>, List<String>>> constructors = List.of(
                values -> new DesktopUiNode.TextToken(null, "key", "", values).arguments(),
                values -> new DesktopUiNode.TableRow("row", values).cells()
        );
        for (var construct : constructors) {
            var input = new ArrayList<>(List.of(" a ", "", "文"));
            var copy = construct.apply(input);
            input.set(0, "changed");
            assertEquals(List.of(" a ", "", "文"), copy);
            assertThrows(UnsupportedOperationException.class, () -> copy.add("new"));
            assertTrue(construct.apply(null).isEmpty());
            assertThrows(NullPointerException.class, () -> construct.apply(Arrays.asList("ok", null)));
            assertEquals(10_000, construct.apply(Collections.nCopies(10_000, "")).size());
            assertThrows(IllegalArgumentException.class, () -> construct.apply(Collections.nCopies(10_001, "")));
            String limit = "a".repeat(65_536);
            assertEquals(List.of(limit), construct.apply(List.of(limit)));
            assertThrows(IllegalArgumentException.class, () -> construct.apply(List.of(limit + "a")));
        }
    }

    @Test
    @DisplayName("多选事件仍拒绝非法或重复标识并隔离可变输入")
    void keepsSelectionValidation() {
        var input = new ArrayList<>(List.of("one", "two"));
        var value = new DesktopUiNode.Value(DesktopUiNode.ValueKind.MULTI_SELECTION, input);
        input.clear();
        assertEquals(List.of("one", "two"), value.values());
        assertThrows(UnsupportedOperationException.class, () -> value.values().clear());
        for (var invalid : List.of(List.of("invalid id"), List.of("one", "one"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DesktopUiNode.Value(DesktopUiNode.ValueKind.MULTI_SELECTION, invalid));
        }
        assertThrows(NullPointerException.class,
                () -> new DesktopUiNode.Value(DesktopUiNode.ValueKind.MULTI_SELECTION, Arrays.asList("one", null)));
    }
}
