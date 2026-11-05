@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.ComposeDesktopUiNodeRenderer
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

/** 目录确认以路径为主，示例按需展开，底部操作不随说明滚动。 */
@Composable
internal fun DirectorySuggestionContent(
    dock: Dock,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = ((dock.center() as Scroll).content() as Container).children()
    val palette = LocalExperiencePalette.current
    var expanded by rememberSaveable(dock.id()) { mutableStateOf(false) }
    val focus = remember(dock.id()) { FocusRequester() }
    LaunchedEffect(dock.id()) { focus.requestFocus() }
    Column(modifier.testTag("directory-suggestion.content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content.forEach { node ->
                    when {
                        node is TextInput -> ComposeDesktopUiNodeRenderer.Render(
                            node, text, emit, Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        node is Text && node.id().endsWith(".plugin") -> CupertinoText(
                            text(node.text()), style = CupertinoTheme.typography.footnote, color = palette.secondaryText,
                        )
                        node is Text && node.id().endsWith(".rule") -> CupertinoText(
                            text(node.text()), Modifier.testTag(node.id()),
                            style = CupertinoTheme.typography.body, color = palette.error,
                        )
                        node is Text && node.id().endsWith(".guidance") -> {
                            CupertinoButton(
                                onClick = { expanded = !expanded },
                                colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.link),
                                contentPadding = PaddingValues(vertical = 6.dp),
                                modifier = Modifier.testTag("directory-suggestion.details").semantics {
                                    stateDescription = text(TextToken.key(if (expanded) "gui.directory-suggestion.expanded" else "gui.directory-suggestion.collapsed"))
                                },
                            ) {
                                CupertinoText((if (expanded) "▾  " else "▸  ") + text(TextToken.key("gui.directory-suggestion.details")),
                                    style = CupertinoTheme.typography.body)
                            }
                            if (expanded) SelectionContainer {
                                CupertinoText(text(node.text()), Modifier.testTag(node.id()),
                                    style = CupertinoTheme.typography.body, color = palette.secondaryText)
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize().wrapContentWidth(Alignment.End))
        }
        content.filterIsInstance<Text>().firstOrNull { it.id().endsWith(".notice") }?.let { notice ->
            val failed = notice.style() == TextStyle.ERROR
            CupertinoText(
                text(notice.text()),
                Modifier.testTag(notice.id()).then(if (failed) Modifier.semantics { error(text(notice.text())) } else Modifier),
                style = CupertinoTheme.typography.footnote,
                color = if (failed) palette.error else palette.secondaryText,
            )
        }
        CupertinoHorizontalDivider(color = palette.separator)
        ComposeDesktopUiNodeRenderer.Render(dock.bottom(), text, emit, Modifier.fillMaxWidth())
    }
}
