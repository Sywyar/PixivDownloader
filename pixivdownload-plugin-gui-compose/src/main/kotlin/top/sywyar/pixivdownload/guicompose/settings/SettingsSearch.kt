package top.sywyar.pixivdownload.guicompose.settings

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SettingLocation
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.Tab
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken

internal data class SettingsSearchResult(
    val location: SettingLocation,
    val helpMatched: Boolean,
)

internal fun searchSettings(
    query: String,
    locations: List<SettingLocation>,
    tabs: List<Tab>,
    text: (TextToken) -> String,
): List<SettingsSearchResult> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return emptyList()
    // 拉丁词从词首匹配，避免 AI 命中 domain、maintenance 或 email；中日韩文字仍可按子串检索。
    val patterns = terms.map { term ->
        val boundary = if (term.first().code < 128 && term.first().isLetterOrDigit()) "(?<![\\p{IsLatin}\\p{N}])" else ""
        Regex(boundary + Regex.escape(term), RegexOption.IGNORE_CASE)
    }
    val categories = tabs.associate { it.id() to text(it.title()) }
    return locations.mapNotNull { location ->
        val label = text(location.label())
        val fields = listOf(
            label,
            categories[location.categoryId()].orEmpty(),
            location.help()?.let(text).orEmpty(),
            location.rowId(),
        )
        val matches = patterns.map { pattern -> fields.indexOfFirst { pattern.containsMatchIn(it) } }
        if (matches.any { it < 0 }) null else {
            val rank = if (label.equals(query.trim(), ignoreCase = true)) -1 else matches.max()
            Triple(rank, matches.sum(), SettingsSearchResult(location, 2 in matches))
        }
    }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}
