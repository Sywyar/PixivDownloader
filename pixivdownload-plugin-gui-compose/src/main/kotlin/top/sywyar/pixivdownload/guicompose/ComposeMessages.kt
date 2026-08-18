package top.sywyar.pixivdownload.guicompose

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

internal class ComposeMessages(private val context: DesktopUiContext) {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    fun get(key: String, vararg arguments: String): String =
        context.host().message(key, *arguments)

    fun plugin(key: String, vararg arguments: String): String =
        resolve(DesktopUiNode.TextToken(GuiComposePlugin.ID, key, key, arguments.toList()))

    fun resolve(token: DesktopUiNode.TextToken): String {
        if (token.key().isBlank()) return token.fallback()
        if (token.namespace() == null) {
            return context.host().message(token.key(), *token.arguments().toTypedArray())
        }
        val source = token.namespace()?.let { namespace ->
            context.currentPluginSources().firstOrNull { it.id() == namespace }
        }
        val loader = source?.classLoader() ?: GuiComposePlugin::class.java.classLoader
        val base = "i18n/web/${token.namespace()}"
        val resolution = context.host().resolveLocale(context.host().detectSystemLocale())
        val pattern = resolution.fallbackChain().asSequence()
            .mapNotNull { locale -> exact(loader, base, locale.resourceSuffix())[token.key()] }
            .firstOrNull()
            ?: token.fallback().ifBlank { token.key() }
        return if (token.arguments().isEmpty()) pattern
        else MessageFormat(pattern, resolution.target().toLocale()).format(token.arguments().toTypedArray())
    }

    private fun exact(loader: ClassLoader, base: String, suffix: String): Map<String, String> {
        val resource = "$base${if (suffix.isBlank()) "" else "_$suffix"}.properties"
        val key = "${System.identityHashCode(loader)}:$resource"
        return cache.computeIfAbsent(key) {
            loader.getResourceAsStream(resource)?.use { stream ->
                val properties = Properties()
                properties.load(InputStreamReader(stream, StandardCharsets.UTF_8))
                properties.stringPropertyNames().associateWith(properties::getProperty)
            } ?: emptyMap()
        }
    }
}
