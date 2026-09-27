package top.sywyar.pixivdownload.guicompose

import java.io.Reader
import java.util.Properties

internal object HomeTips {
    const val NAMESPACE = "gui-compose-tips"
    const val BASE_NAME = "i18n.web.tips"

    val keys: List<String> by lazy {
        HomeTips::class.java.getResourceAsStream("/i18n/web/tips.properties")
            ?.reader(Charsets.UTF_8)?.use(::readKeys).orEmpty()
    }

    fun readKeys(reader: Reader): List<String> {
        val properties = Properties().apply { load(reader) }
        return properties.stringPropertyNames()
            .filter { it.startsWith("tip.") && properties.getProperty(it).isNotBlank() }
            .sorted()
    }
}
