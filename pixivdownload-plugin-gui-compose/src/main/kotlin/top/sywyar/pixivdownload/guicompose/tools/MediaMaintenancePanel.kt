@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

@Composable
internal fun MediaMaintenancePanel(
    group: Group,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val nodes = descendants(group).toList()
    fun message(suffix: String) = nodes.filterIsInstance<Text>().firstOrNull { it.id() == group.id() + suffix }
    fun button(suffix: String) = nodes.filterIsInstance<Button>().first { it.id() == group.id() + suffix }
    fun label(key: String) = workspaceText(text, key)
    val progress = nodes.filterIsInstance<Progress>().firstOrNull { it.id() == group.id() + ".progress" }
    val busy = nodes.filterIsInstance<Progress>().any { it.id() == group.id() + ".busy" }
    val ready = message(".ready")
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (progress == null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val choices = nodes.filterIsInstance<Choice>()
                val stacked = maxWidth < 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    choices.forEach { MediaFormatField(it, text, emit, Modifier.fillMaxWidth()) }
                } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    choices.forEach { MediaFormatField(it, text, emit, Modifier.weight(1f)) }
                }
            }
            val thumbnails = nodes.filterIsInstance<Toggle>().single()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CupertinoText(text(thumbnails.label()), Modifier.weight(1f), fontSize = 13.sp)
                ComposeDesktopUiNodeRenderer.FormContent(
                    Toggle(
                        thumbnails.id(),
                        thumbnails.bindingId(),
                        thumbnails.label(),
                        null,
                        ToggleStyle.SWITCH,
                        thumbnails.selected(),
                        thumbnails.enabled(),
                    ),
                    text,
                    emit,
                    Modifier.testTag(thumbnails.id()),
                )
            }
            message(".help")?.let { ToolNotice(text(it.text())) }
        }
        if (ready != null) {
            CupertinoText(
                text(ready.text()),
                modifier = Modifier.testTag(ready.id()).semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        } else message(".state")?.let { state ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CupertinoText(
                    text(state.text()),
                    modifier = Modifier.testTag(state.id()).semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (progress != null) {
                    DesktopLinearProgress(
                        progress.progress().toFloat().takeUnless { progress.indeterminate() },
                        Modifier.fillMaxWidth().height(5.dp),
                    )
                    CupertinoText(text(progress.text()), fontSize = 12.sp, color = palette.secondaryText)
                    CupertinoText(label("running-background"), fontSize = 12.sp, color = palette.secondaryText)
                } else {
                    message(".summary")?.let { CupertinoText(text(it.text()), fontSize = 12.sp, color = palette.secondaryText) }
                    ToolAction(button(".refresh"), text, emit)
                }
            }
        }
        message(".files")?.let { files ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        text(files.text()).lines().forEachIndexed { index, file ->
                            CupertinoText(file, fontSize = 13.sp)
                            listOf(".formats", ".thumbnail").forEach { suffix ->
                                message(".file.$index$suffix")?.let { CupertinoText(text(it.text()), fontSize = 11.sp, color = palette.secondaryText) }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
                        }
                    }
                }
                val next = button(".next")
                val previous = button(".previous")
                if (next.enabled() || previous.enabled()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        message(".page")?.let { CupertinoText(text(it.text()), Modifier.weight(1f), fontSize = 12.sp) }
                        ToolAction(previous, text, emit)
                        ToolAction(next, text, emit)
                    }
                }
            }
        }
        message(".notice")?.let {
            CupertinoText(
                text(it.text()),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; error(text(it.text())) },
                fontSize = 12.sp,
                color = palette.error,
            )
        }
        listOf(".skipped", ".limited").forEach { suffix ->
            message(suffix)?.let { ToolNotice(text(it.text()), warning = true) }
        }
    }
}

@Composable
internal fun MediaMaintenanceActions(
    group: Group,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val nodes = descendants(group).toList()
    fun button(suffix: String) = nodes.filterIsInstance<Button>().first { it.id() == group.id() + suffix }
    val cancel = button(".cancel")
    val busy = nodes.any { it.id() == group.id() + ".busy" }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CupertinoActivityIndicator(Modifier.size(14.dp))
        if (cancel.enabled() || nodes.any { it.id() == group.id() + ".progress" }) {
            ToolAction(cancel, text, emit)
        } else {
            val start = button(".start")
            ToolAction(button(".preview"), text, emit, primary = !start.enabled())
            if (start.enabled()) ToolAction(start, text, emit, primary = true)
        }
    }
}

@Composable
internal fun MediaCapabilities(
    group: Group,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val nodes = descendants(group).toList()
    fun message(suffix: String) = nodes.filterIsInstance<Text>().firstOrNull { it.id() == group.id() + suffix }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CupertinoText(text(message(".cap-title")!!.text()), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        ToolAction(nodes.filterIsInstance<Button>().first { it.id() == group.id() + ".check" }, text, emit)
        if (nodes.any { it.id() == group.id() + ".busy" }) CupertinoActivityIndicator(Modifier.size(14.dp))
        message(".command")?.let {
            SelectionContainer { CupertinoText(text(it.text()), fontSize = 12.sp, color = palette.secondaryText) }
        }
        nodes.filterIsInstance<Text>().filter { it.id().startsWith(group.id() + ".cap.") && it.id().endsWith(".name") }.forEach { name ->
            val result = nodes.filterIsInstance<Text>().first { it.id() == name.id().removeSuffix(".name") + ".result" }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CupertinoText(text(name.text()), Modifier.weight(1f), fontSize = 13.sp)
                CupertinoText(text(result.text()), fontSize = 13.sp, color = palette.secondaryText)
            }
        }
        message(".notice")?.let { ToolNotice(text(it.text()), warning = true) }
    }
}

@Composable
private fun MediaFormatField(
    choice: Choice,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CupertinoText(text(choice.label()), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        ComposeDesktopUiNodeRenderer.FormContent(choice, text, emit, Modifier.fillMaxWidth().testTag(choice.id()))
        choice.help()?.let { CupertinoText(text(it), fontSize = 11.sp, color = LocalExperiencePalette.current.secondaryText) }
    }
}
