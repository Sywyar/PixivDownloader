package top.sywyar.pixivdownload.plugin.api.gui.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("声明式桌面 UI 节点")
class DesktopUiNodeTest {

    @Test
    @DisplayName("节点树拒绝全局重复标识")
    void rejectsDuplicateNodeIds() {
        DesktopUiNode root = new DesktopUiNode.Container(
                "root", DesktopUiNode.ContainerLayout.COLUMN, 1, 0,
                DesktopUiNode.Alignment.START, List.of(
                        text("duplicate"),
                        new DesktopUiNode.Group("group", DesktopUiNode.TextToken.raw("Group"),
                                text("duplicate"))));

        assertThatThrownBy(() -> DesktopUiNode.validateTree(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate node id");
    }

    @Test
    @DisplayName("密码节点禁止携带可长期保存的初始值")
    void rejectsPasswordInitialValue() {
        assertThatThrownBy(() -> new DesktopUiNode.TextInput(
                "password", "account.password", DesktopUiNode.TextToken.raw("Password"), null,
                DesktopUiNode.InputKind.PASSWORD, "secret", 20, 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry an initial value");
    }

    @Test
    @DisplayName("复选组必须使用多选语义")
    void rejectsSingleSelectionCheckboxGroup() {
        assertThatThrownBy(() -> new DesktopUiNode.Choice(
                "choice", "choice.value", DesktopUiNode.TextToken.raw("Choice"), null,
                DesktopUiNode.ChoiceStyle.CHECK_BOXES, DesktopUiNode.SelectionMode.SINGLE,
                List.of(new DesktopUiNode.Option("one", DesktopUiNode.TextToken.raw("One"), true)),
                List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires multiple selection");
    }

    @Test
    @DisplayName("事件类型和值类型必须保持一致")
    void rejectsMismatchedEventValue() {
        assertThatThrownBy(() -> new DesktopUiNode.Event(
                DesktopUiNode.EventType.ACTIVATE, "button", "action.run", DesktopUiNode.Value.bool(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a value");
    }

    private static DesktopUiNode.Text text(String id) {
        return new DesktopUiNode.Text(id, DesktopUiNode.TextToken.raw(id),
                DesktopUiNode.TextStyle.BODY, false, false);
    }
}
