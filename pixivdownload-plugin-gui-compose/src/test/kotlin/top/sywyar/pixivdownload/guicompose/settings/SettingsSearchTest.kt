package top.sywyar.pixivdownload.guicompose.settings

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import kotlin.test.*

@DisplayName("设置搜索的相关性和词边界")
class SettingsSearchTest {
    @Test
    @DisplayName("短英文查询不命中无关技术标识，仍能找到分类和词首")
    fun shortTermsRespectWordBoundaries() {
        val results = search("AI", listOf(
            location("ssl.domain", "Service domain"),
            location("maintenance.enabled", "Enable scheduled upkeep"),
            location("notifications.email", "Notifications", "Send messages by email"),
            location("plugin.model", "Model", category = "translation"),
            location("custom.hint", "Extra", "Connect to an AI endpoint"),
        ))
        assertEquals(listOf("plugin.model", "custom.hint"), results.map { it.location.rowId() })
        assertFalse(results.first().helpMatched)
        assertTrue(results.last().helpMatched)
        assertEquals(listOf("maintenance.enabled"),
            search("maint", listOf(location("maintenance.enabled", "Scheduled upkeep"))).map { it.location.rowId() })
        assertEquals("translation.model", search("AI", listOf(location("translation.model", "使用AI模型")))
            .single().location.rowId())
    }

    @Test
    @DisplayName("标题命中优先于分类、说明和标识，说明命中保留可解释信息")
    fun ranksVisibleFieldsBeforeHelpAndIdentifiers() {
        val results = search("服务", listOf(
            location("downloads.parallel", "并发下载数", "下载服务使用的并发数"),
            location("server.port", "端口", category = "service"),
            location("ssl.domain", "服务域名"),
            location("app.service", "服务"),
        ))
        assertEquals(listOf("app.service", "ssl.domain", "server.port", "downloads.parallel"), results.map { it.location.rowId() })
        assertTrue(results.last().helpMatched)
        assertFalse(results.first().helpMatched)
        assertEquals("ssl.domain", search("ssl.dom", listOf(location("ssl.domain", "服务域名"))).single().location.rowId())
    }

    @Test
    @DisplayName("多词可跨标题和分类匹配，空白及不匹配查询不返回结果")
    fun matchesAllTermsAcrossFields() {
        val locations = listOf(location("plugin.model", "Model", category = "translation"), location("app.port", "Port"))
        assertEquals("plugin.model", search(" ai   mod ", locations).single().location.rowId())
        assertTrue(search("ai port", locations).isEmpty())
        assertTrue(search(" ", locations).isEmpty())
    }

    private fun search(query: String, locations: List<SettingLocation>) = searchSettings(
        query,
        locations,
        listOf(Tab("general", TextToken.raw("General"), Spacer("general.empty", 0, 0)),
            Tab("translation", TextToken.raw("AI translation"), Spacer("translation.empty", 0, 0)),
            Tab("service", TextToken.raw("本地服务"), Spacer("service.empty", 0, 0))),
        TextToken::fallback,
    )

    private fun location(id: String, title: String, help: String = "", category: String = "general") = SettingLocation(
        id, category, TextToken.raw(title), help.takeIf(String::isNotBlank)?.let(TextToken::raw),
        Button("$id.locate", "$id.locate", TextToken.raw(title), null, ButtonStyle.NORMAL, true),
    )
}
