package top.sywyar.pixivdownload.guicompose

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

internal class ComposeMessages(private val context: DesktopUiContext) {
    fun plugin(key: String, vararg arguments: String): String = resolve(
        DesktopUiNode.TextToken(GuiComposePlugin.ID, key, key, arguments.toList()),
    )

    fun resolve(token: DesktopUiNode.TextToken): String {
        if (token.key().isBlank()) return token.fallback()
        return context.resolveText(
            DesktopUiText(token.namespace(), token.key(), token.fallback(), token.arguments()),
        )
    }
}
