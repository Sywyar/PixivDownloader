package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 桌面网页入口与托盘导航构建器。
 */
final class DesktopNavigationView {
    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final DesktopStatusController status;

    DesktopNavigationView(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            DesktopStatusController status
    ) {
        this.owner = owner;
        this.host = host;
        this.status = status;
    }

    private List<NavigationContribution> webEntries(String placement) {
        List<NavigationContribution> entries = new ArrayList<>();
        for (DesktopUiPluginSource source : owner.currentSources()) {
            try {
                for (NavigationContribution contribution : source.plugin().navigation()) {
                    if (contribution != null
                            && contribution.placements().contains(placement)
                            && contribution.visibleTo() != null
                            && contribution.visibleTo().supportsUiVisibility()
                            && safeHref(contribution.href())) {
                        entries.add(contribution);
                    }
                }
            } catch (RuntimeException ignored) {
                // 隔离可选插件条目异常。
            }
        }
        entries.sort(Comparator.comparingInt(NavigationContribution::priority)
                .thenComparing(NavigationContribution::id));
        return entries;
    }

    List<DesktopUiNode> webEntryButtons(
            String placement,
            String base,
            Map<String, Runnable> nextActions
    ) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (NavigationContribution entry : webEntries(placement)) {
            String id = base + "." + index++;
            String action = id + ".open";
            nextActions.put(action, () -> owner.openWeb(entry.href()));
            nodes.add(new DesktopUiNode.Button(
                    id,
                    action,
                    token(entry.labelNamespace(), entry.labelI18nKey(), entry.id()),
                    null,
                    ButtonStyle.NORMAL,
                    true
            ));
        }
        return nodes;
    }

    DesktopUiDocument.Tray tray(Map<String, Runnable> nextActions) {
        List<DesktopUiDocument.TrayItem> items = new ArrayList<>();
        items.add(DesktopUiDocument.TrayItem.activate(
                "tray.show",
                key("gui.tray.menu.show-main-window")
        ));
        items.add(DesktopUiDocument.TrayItem.separator("tray.separator.actions"));
        nextActions.put("tray.batch.open", () -> owner.openWeb("/pixiv-batch.html"));
        items.add(DesktopUiDocument.TrayItem.dispatch(
                "tray.batch",
                key("gui.action.open-batch"),
                "tray.batch.open"
        ));
        int index = 0;
        for (NavigationContribution entry : webEntries(NavigationPlacements.GUI_TRAY_ACTIONS)) {
            String id = "tray.web." + index++;
            String action = id + ".open";
            nextActions.put(action, () -> owner.openWeb(entry.href()));
            items.add(DesktopUiDocument.TrayItem.dispatch(
                    id,
                    token(entry.labelNamespace(), entry.labelI18nKey(), entry.id()),
                    action
            ));
        }
        nextActions.put("tray.download-folder.open", status::openDownloadDirectory);
        items.add(DesktopUiDocument.TrayItem.dispatch(
                "tray.download-folder",
                key("gui.action.open-download-directory"),
                "tray.download-folder.open"
        ));
        items.add(DesktopUiDocument.TrayItem.separator("tray.separator.exit"));
        nextActions.put("tray.exit", host::requestApplicationExit);
        items.add(DesktopUiDocument.TrayItem.dispatch(
                "tray.exit",
                key("gui.action.exit"),
                "tray.exit"
        ));
        return new DesktopUiDocument.Tray(TextToken.raw(host.applicationName()), items);
    }
}
