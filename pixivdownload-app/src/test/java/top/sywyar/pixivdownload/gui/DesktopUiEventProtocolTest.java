package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopUiEventProtocolTest {

    @Test
    @DisplayName("当前文档索引拒绝禁用选项、伪造行和树项以及越界数字")
    void currentDocumentIndexRejectsForgedSelectionsAndInvalidNumbers() {
        DesktopUiNode.TextToken label = DesktopUiNode.TextToken.raw("Label");
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(
                "choice",
                "choice.value",
                label,
                null,
                DesktopUiNode.ChoiceStyle.COMBO_BOX,
                DesktopUiNode.SelectionMode.SINGLE,
                List.of(new DesktopUiNode.Option("disabled-option", label, false)),
                List.of(),
                true
        );
        DesktopUiNode.Table table = new DesktopUiNode.Table(
                "table",
                "table.value",
                List.of(new DesktopUiNode.TableColumn("value", label, 0)),
                List.of(new DesktopUiNode.TableRow("row", List.of("Row"))),
                DesktopUiNode.SelectionMode.SINGLE,
                List.of(),
                true
        );
        DesktopUiNode.Tree tree = new DesktopUiNode.Tree(
                "tree",
                "tree.value",
                List.of(new DesktopUiNode.TreeItem("item", label, List.of())),
                DesktopUiNode.SelectionMode.SINGLE,
                List.of(),
                true
        );
        DesktopUiNode.NumberInput number = new DesktopUiNode.NumberInput(
                "number",
                "number.value",
                label,
                null,
                DesktopUiNode.NumberStyle.SPINNER,
                1,
                1,
                9,
                2,
                true
        );
        Map<String, DesktopUiEventProtocol.EventEndpoint> endpoints = DesktopUiEventProtocol.index(
                document(
                        choice,
                        table,
                        tree,
                        number
                ));

        assertThat(DesktopUiEventProtocol.validate(
                endpoints.get(choice.id()),
                event(
                        DesktopUiNode.EventType.SELECTION,
                        choice.id(),
                        DesktopUiNode.Value.selection("disabled-option")
                )
        )).isEqualTo("choice option is disabled");
        assertThat(DesktopUiEventProtocol.validate(
                endpoints.get(table.id()),
                event(
                        DesktopUiNode.EventType.SELECTION,
                        table.id(),
                        DesktopUiNode.Value.selection("forged-row")
                )
        )).isEqualTo("unknown table row");
        assertThat(DesktopUiEventProtocol.validate(
                endpoints.get(tree.id()),
                event(
                        DesktopUiNode.EventType.SELECTION,
                        tree.id(),
                        DesktopUiNode.Value.selection("forged-item")
                )
        )).isEqualTo("unknown tree item");
        assertThat(DesktopUiEventProtocol.validate(
                endpoints.get(number.id()),
                event(
                        DesktopUiNode.EventType.CHANGE,
                        number.id(),
                        DesktopUiNode.Value.number(10)
                )
        )).isEqualTo("number is outside bounds");
        assertThat(DesktopUiEventProtocol.validate(
                endpoints.get(number.id()),
                event(
                        DesktopUiNode.EventType.CHANGE,
                        number.id(),
                        DesktopUiNode.Value.number(4)
                )
        )).isEqualTo("number does not align with step");
    }

    private static DesktopUiDocument document(DesktopUiNode... nodes) {
        return new DesktopUiDocument(List.of(new DesktopUiDocument.Page(
                "page",
                DesktopUiNode.TextToken.raw("Page"),
                new DesktopUiNode.Container(
                        "root",
                        DesktopUiNode.ContainerLayout.COLUMN,
                        1,
                        0,
                        DesktopUiNode.Alignment.STRETCH,
                        List.of(nodes)
                )
        )));
    }

    private static DesktopUiNode.Event event(
            DesktopUiNode.EventType type,
            String nodeId,
            DesktopUiNode.Value value
    ) {
        return new DesktopUiNode.Event(
                0,
                0,
                type,
                nodeId,
                value
        );
    }
}
