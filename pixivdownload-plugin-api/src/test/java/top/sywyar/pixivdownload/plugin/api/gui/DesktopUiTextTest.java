package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopUiTextTest {
    @Test
    @DisplayName("文本保留规范化和标识校验边界")
    void normalizesAndValidatesIdentifiers() {
        for (String id : List.of("a", "0", "a.b:c_d-e", "a".repeat(128))) {
            var text = new DesktopUiText(" " + id + " ", "\t" + id + "\n", null, null);
            assertThat(text.namespace()).isEqualTo(id);
            assertThat(text.key()).isEqualTo(id);
            assertThat(text.fallback()).isEmpty();
            assertThat(text.arguments()).isEmpty();
        }
        assertThat(new DesktopUiText(" ", null, " text ", null).namespace()).isNull();
        assertThat(DesktopUiText.raw(" text ").fallback()).isEqualTo(" text ");
        assertThat(DesktopUiText.key("key").fallback()).isEqualTo("key");
        for (String id : List.of("-a", "a/b", "a b", "中文", "a".repeat(129))) {
            assertThatThrownBy(() -> DesktopUiText.plugin(id, "key", "fallback"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DesktopUiText.key(id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DesktopUiText.raw(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("参数为不可变快照且拒绝空元素和超限输入")
    void freezesArgumentsAndKeepsBounds() {
        var input = new ArrayList<>(List.of(" a ", "", "文"));
        var text = new DesktopUiText(null, "key", "fallback", input);
        input.set(0, "changed");
        assertThat(text.arguments()).containsExactly(" a ", "", "文");
        assertThatThrownBy(() -> text.arguments().add("new")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new DesktopUiText(null, "key", "", Arrays.asList("ok", null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(new DesktopUiText(null, "key", "", Collections.nCopies(4_096, "")).arguments()).hasSize(4_096);
        assertThatThrownBy(() -> new DesktopUiText(null, "key", "", Collections.nCopies(4_097, "")))
                .isInstanceOf(IllegalArgumentException.class);
        String limit = "a".repeat(16_384);
        assertThat(DesktopUiText.raw(limit).fallback()).isEqualTo(limit);
        assertThat(new DesktopUiText(null, "key", "", List.of(limit)).arguments()).containsExactly(limit);
        assertThatThrownBy(() -> DesktopUiText.raw(limit + "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DesktopUiText(null, "key", "", List.of(limit + "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
