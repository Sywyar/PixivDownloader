@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

@Composable
internal fun FfmpegToolsWorkspace(
    media: Group,
    tools: List<Group>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    back: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    var section by rememberSaveable { mutableStateOf(if (tools.isEmpty()) "ffmpeg" else tools.first().id()) }
    val selected = tools.firstOrNull { it.id() == section }
    LaunchedEffect(tools.map { it.id() }) {
        if (section != "ffmpeg" && selected == null) section = "ffmpeg"
    }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocus.requestFocus() }
    fun label(key: String) = workspaceText(text, key)
    BoxWithConstraints(modifier.fillMaxSize().testTag("tools.media.workspace")) {
        val inset = if (maxWidth < 600.dp) 24.dp else 44.dp
        Column(
            Modifier.fillMaxSize().onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    back()
                    true
                } else false
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CupertinoButton(
                    onClick = back,
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.link),
                    modifier = Modifier.testTag("tools.media.back").focusRequester(backFocus),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    DesktopIcon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), palette.link)
                    Spacer(Modifier.width(8.dp))
                    CupertinoText(label("back"), fontSize = 13.sp, color = palette.link)
                }
                CupertinoText(
                    label("media-title"),
                    Modifier.semantics { heading(); paneTitle = label("media-title") },
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sections = listOf("ffmpeg" to label("media-management")) + tools.map { it.id() to text(it.title()) }
                    sections.forEach { (id, title) ->
                        Box(
                            Modifier.testTag("tools.media.tab.$id")
                                .background(if (section == id) palette.selection else palette.secondarySurface, RoundedCornerShape(9.dp))
                                .selectable(section == id, role = Role.Tab) { section = id }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            CupertinoText(title, fontSize = 13.sp, color = if (section == id) palette.link else palette.secondaryText)
                        }
                    }
                }
            }
            key(section) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = inset, vertical = 12.dp),
                    ) {
                        Column(
                            Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            if (selected != null) MediaMaintenancePanel(selected, text, emit)
                            else {
                                FfmpegManagement(media, text, emit)
                                tools.forEach { tool ->
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
                                    MediaCapabilities(tool, text, emit)
                                }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (selected != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
                Box(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 18.dp)) {
                    MediaMaintenanceActions(selected, text, emit)
                }
            }
        }
    }
}

@Composable
private fun FfmpegManagement(
    media: Group,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val nodes = descendants(media).toList()
    val palette = LocalExperiencePalette.current
    val buttons = nodes.filterIsInstance<Button>()
    val form = nodes.filterIsInstance<Form>().firstOrNull()
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        nodes.filterIsInstance<Text>().firstOrNull { it.id() == "status.ffmpeg.state" }?.let {
            CupertinoText(text(it.text()), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        nodes.filterIsInstance<Text>().filter {
            it.id() in listOf("status.ffmpeg.source", "status.ffmpeg.path", "status.ffmpeg.notice", "status.ffmpeg.confirmation")
        }.forEach {
            SelectionContainer {
                CupertinoText(text(it.text()), fontSize = 12.sp, color = if (it.style() == TextStyle.ERROR) palette.error else palette.secondaryText)
            }
        }
        nodes.filterIsInstance<Progress>().forEach {
            DesktopLinearProgress(it.progress().toFloat().takeUnless { _ -> it.indeterminate() }, Modifier.fillMaxWidth().height(5.dp))
            CupertinoText(text(it.text()), fontSize = 12.sp, color = palette.secondaryText)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons.filter {
                it.id() in listOf("status.ffmpeg.refresh", "status.ffmpeg.open", "status.ffmpeg.help", "ffmpeg.confirm.cancel", "status.ffmpeg.install", "ffmpeg.confirm.install")
            }.forEach { ToolAction(it, text, emit, primary = it.id() == "ffmpeg.confirm.install") }
        }
        if (form != null) {
            ToolDisclosure(workspaceText(text, "media-existing"), "tools.media.advanced", advanced) { advanced = !advanced }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    form.rows().forEach { ToolField(it.content() as TextInput, text, emit, help = null) }
                    buttons.firstOrNull { it.id() == "status.ffmpeg.path.save" }?.let { ToolAction(it, text, emit) }
                }
            }
        }
    }
}
