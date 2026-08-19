package top.sywyar.pixivdownload.guicompose

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution

class GuiComposePlugin : PixivFeaturePlugin, DesktopUiProvider {
    override fun id(): String = ID
    override fun displayName(): String = "plugin.name"
    override fun description(): String = "plugin.summary"
    override fun iconKey(): String = "palette"
    override fun colorToken(): String = "violet"
    override fun kind(): PluginKind = PluginKind.FEATURE
    override fun supportedNodeKinds(): Set<DesktopUiNode.Kind> =
        ComposeDesktopUiNodeRenderer.supportedKinds()

    override fun supportedCapabilities(): Set<DesktopUiCapability> = setOf(
        DesktopUiCapability.SPLIT_USER_RESIZABLE,
        DesktopUiCapability.TABLE_LARGE_DATA_SCROLL,
        DesktopUiCapability.INPUT_NUMERIC,
        DesktopUiCapability.INPUT_PATH_FILE,
        DesktopUiCapability.INPUT_PATH_DIRECTORY,
        DesktopUiCapability.SELECTION_MULTIPLE,
    )

    override fun launch(context: DesktopUiContext): DesktopUiSession = ComposeDesktopUi.launch(context)

    override fun i18n(): List<I18nContribution> =
        listOf(I18nContribution(ID, "i18n.web.gui-compose", 8))

    companion object {
        const val ID = "gui-compose"
    }
}
