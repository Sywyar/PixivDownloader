package top.sywyar.pixivdownload.guicompose

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode

internal class ComposeMessages(private val context: DesktopUiContext) {
    fun plugin(key: String, vararg arguments: String): String = resolve(
        DesktopUiNode.TextToken(GuiComposePlugin.ID, key, key, arguments.toList()),
    )

    fun resolve(token: DesktopUiNode.TextToken): String = context.resolveText(token)
}
