package top.sywyar.pixivdownload.guicompose.about

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.CupertinoText
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.AboutFact
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken

@Composable
internal fun AboutPlatformFact(fact: AboutFact, text: (TextToken) -> String) {
    val palette = LocalExperiencePalette.current
    val tag = "about.platform.${fact.id()}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 520.dp * LocalDensity.current.fontScale
        val label: @Composable () -> Unit = {
            SelectionContainer {
                CupertinoText(
                    text(fact.label()),
                    Modifier.padding(top = if (fact.expandable() && !stacked) 4.dp else 0.dp)
                        .testTag("$tag.label"),
                    fontSize = 12.sp,
                    color = palette.secondaryText
                )
            }
        }
        val value: @Composable () -> Unit = {
            if (fact.expandable()) AboutFactDisclosure(fact, text)
            else SelectionContainer {
                CupertinoText(text(fact.value()), Modifier.testTag(tag), fontSize = 12.sp)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            label()
            value()
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.weight(.38f)) { label() }
            Box(Modifier.weight(.62f)) { value() }
        }
    }
}

@Composable
private fun AboutFactDisclosure(fact: AboutFact, text: (TextToken) -> String) {
    val palette = LocalExperiencePalette.current
    val tag = "about.platform.${fact.id()}"
    val value = text(fact.value())
    val entries = remember(value) { value.lines().filter(String::isNotBlank) }
    var expanded by rememberSaveable(fact.id()) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 1f))
    val state = text(TextToken(
        "gui-compose",
        if (expanded) "gui.compose.expanded" else "gui.compose.collapsed",
        "",
        emptyList()
    ))
    Column(Modifier.fillMaxWidth().testTag("$tag.group")) {
        AboutAction(
            "$tag.toggle",
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = state }
        ) {
            Row(
                Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DesktopIcon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(12.dp).graphicsLayer { rotationZ = angle },
                    tint = palette.secondaryText
                )
                CupertinoText(
                    text(TextToken("gui-compose", "gui.compose.about.plugin-count", "", listOf(entries.size.toString()))),
                    fontSize = 12.sp
                )
            }
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), shrinkTowards = Alignment.Top) + fadeOut(tween(100))
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, bottom = 6.dp).testTag(tag)) {
                CupertinoText(
                    aboutText(text, "plugin-format"),
                    Modifier.padding(bottom = 6.dp),
                    fontSize = 11.sp,
                    color = palette.secondaryText
                )
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entries.forEachIndexed { index, entry ->
                            CupertinoText(
                                entry,
                                Modifier.fillMaxWidth().testTag("$tag.entry.$index"),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
