package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopUiPageContributionTest {
    @Test
    @DisplayName("页面贡献冻结动作与对话框快照")
    void defensivelyCopiesCollections() {
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put("fixture.page.refresh", "fixture/refresh");
        ArrayList<DesktopUiDocument.Dialog> dialogs = new ArrayList<>();
        DesktopUiPageContribution contribution = new DesktopUiPageContribution(
                "fixture.page", 10, DesktopUiNode.TextToken.raw("Fixture"), text("fixture.page.content"),
                actions, dialogs);

        actions.clear();
        dialogs.add(new DesktopUiDocument.Dialog("fixture.page.dialog", DesktopUiNode.TextToken.raw("Dialog"),
                DesktopUiDocument.DialogStyle.INFO, text("fixture.page.dialog.content"),
                "fixture.page.dialog.dismiss", false, 0, 0));

        assertThat(contribution.actions()).containsExactlyEntriesOf(
                java.util.Map.of("fixture.page.refresh", "fixture/refresh"));
        assertThat(contribution.dialogs()).isEmpty();
    }

    private static DesktopUiNode text(String id) {
        return new DesktopUiNode.Text(id, DesktopUiNode.TextToken.raw(id),
                DesktopUiNode.TextStyle.BODY, true, false);
    }
}
