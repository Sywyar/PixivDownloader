@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.security

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.tools.ToolIcon
import top.sywyar.pixivdownload.guicompose.tools.ToolPanelLayout

/**
 * 程序访问密钥整页：列表、编辑、确认与一次性秘密都在安全页内容区内切换，左上角返回安全概览。
 * 撤销和公网 HTTP 例外仍使用居中的确认弹窗；离开页面时由控制器清除一次性秘密。
 */
@Composable
internal fun SecurityProgramKeysPage(
    node: SecurityOverview,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = node.programKeys() ?: return
    val palette = LocalExperiencePalette.current
    fun label(key: String, vararg args: Any) = text(TextToken("security", key, "", args.map(Any::toString)))
    fun enabled(id: String) = node.actions().firstOrNull { it.id() == id }?.enabled() == true
    fun activate(id: String) {
        if (enabled(id)) emit(Event(EventType.ACTIVATE, id, Value.empty()))
    }
    @Composable fun action(name: String, title: String = name, primary: Boolean = false, destructive: Boolean = false,
                           modifier: Modifier = Modifier) {
        val id = "program.keys.$name"
        SecurityButton(label(title), id, enabled(id), primary = primary, destructive = destructive, modifier = modifier) { activate(id) }
    }
    val mode = view.mode()
    val listing = mode == "list" || mode == "revoke" || mode == "transport"
    val back = { activate("security.close") }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocus.requestFocus() }

    BoxWithConstraints(modifier.fillMaxSize().testTag("program.keys.page")) {
        val narrow = maxWidth < 640.dp
        val inset = if (maxWidth < 600.dp) 24.dp else 44.dp
        Column(
            Modifier.fillMaxSize().onKeyEvent {
                if (it.type != KeyEventType.KeyDown || it.key != Key.Escape) return@onKeyEvent false
                when (mode) {
                    "list" -> back()
                    "review" -> activate("program.keys.back")
                    "edit", "prompt" -> activate("program.keys.cancel")
                    else -> return@onKeyEvent false
                }
                true
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 20.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CupertinoButton(
                    onClick = back,
                    enabled = enabled("security.close"),
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.link),
                    modifier = Modifier.testTag("program.keys.leave").focusRequester(backFocus),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    DesktopIcon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), palette.link)
                    Spacer(Modifier.width(8.dp))
                    CupertinoText(label("keys.back"), fontSize = 13.sp, color = palette.link)
                }
                val title = when (mode) {
                    "edit" -> label(if (view.creating()) "create" else "edit")
                    "review" -> label("review.title")
                    "created" -> label("created.title")
                    "prompt" -> label("instructions.title")
                    else -> label("keys.title")
                }
                @Composable fun toolbar() {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        action("refresh")
                        action("create", primary = true)
                    }
                }
                Row(Modifier.widthIn(max = 880.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CupertinoText(title, Modifier.semantics { heading(); paneTitle = title },
                            fontSize = if (narrow) 26.sp else 30.sp, lineHeight = if (narrow) 32.sp else 36.sp,
                            fontWeight = FontWeight.Bold)
                        if (listing) {
                            CupertinoText(label("keys.description"), fontSize = 13.sp, color = palette.secondaryText)
                        }
                    }
                    if (listing && !narrow) {
                        Spacer(Modifier.width(16.dp))
                        toolbar()
                    }
                }
                if (listing && narrow) toolbar()
            }
            key(if (listing) "list" else mode) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll)
                            .padding(start = inset, end = inset, top = 10.dp, bottom = 28.dp),
                    ) {
                        Column(
                            Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            val error = view.notice()?.takeUnless { it.key() == "copied" || it.key() == "copy.manual" }
                            SecurityError(error?.let(text))
                            if (view.uncertain()) DesktopNotice(label("request.check-before-retry"), TextStyle.WARNING)
                            when {
                                listing -> KeyList(node, view, narrow, ::label, text, ::enabled, ::activate)
                                mode == "edit" -> KeyEditor(view, ::label, text, emit, ::activate, ::enabled)
                                mode == "review" -> KeyReview(view, narrow, ::label, text)
                                mode == "created" -> CreatedKey(view, ::label, text, enabled("program.keys.copy")) {
                                    activate("program.keys.copy")
                                }
                                mode == "prompt" -> PromptKey(view, ::label, text)
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (!listing) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
                FlowRow(
                    Modifier.padding(horizontal = inset, vertical = 16.dp).widthIn(max = 880.dp).fillMaxWidth()
                        .testTag("program.keys.footer"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (mode) {
                        "edit" -> { action("cancel"); action("review", primary = true) }
                        "review" -> { action("back", "review.back"); action("save", primary = true) }
                        "created", "prompt" -> action("cancel", "done", primary = true)
                    }
                }
            }
        }
    }

    val confirming = mode == "revoke" || mode == "transport"
    val lastConfirm = remember { arrayOf("revoke") }
    if (confirming) lastConfirm[0] = mode
    SecuritySheet(if (confirming) "confirm" else "", !node.busy(), { activate("program.keys.cancel") }, {}) {
        val revoking = lastConfirm[0] == "revoke"
        val next = view.nextTransport()
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        ToolPanelLayout(
            if (revoking) label("revoke") else label("transport.$next"),
            view.targetName(),
            if (revoking) Icons.Default.Delete else Icons.Default.Public,
            { activate("program.keys.cancel") }, label("cancel"), !node.busy(), "program.keys.confirm",
            footer = {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    action("cancel", modifier = Modifier.focusRequester(cancelFocus))
                    if (revoking) action("revoke", primary = true, destructive = true)
                    else action("confirm", "transport.$next", primary = true, destructive = next == "HTTP_ALLOWED")
                }
            },
        ) {
            CupertinoText(
                if (revoking) label("revoke.help").replace("{name}", view.targetName()) else label("transport.$next.help"),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun KeyList(
    node: SecurityOverview,
    view: ProgramKeysView,
    narrow: Boolean,
    label: (String, Array<out Any>) -> String,
    text: (TextToken) -> String,
    enabled: (String) -> Boolean,
    activate: (String) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    fun l(key: String) = label(key, emptyArray())
    when {
        view.keys().isEmpty() && !view.ready() -> if (node.busy()) {
            Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DesktopLinearProgress(null, Modifier.width(120.dp).height(4.dp))
                CupertinoText(l("keys.loading"), fontSize = 13.sp, color = palette.secondaryText)
            }
        }
        view.keys().isEmpty() -> Column(
            Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("program.keys.empty"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ToolIcon(Icons.Default.Key)
            CupertinoText(l("empty"), fontSize = 14.sp, color = palette.secondaryText)
            CupertinoText(l("empty.description"), Modifier.widthIn(max = 460.dp), fontSize = 13.sp,
                color = palette.secondaryText, textAlign = TextAlign.Center)
            SecurityButton(l("create"), "program.keys.empty.create", enabled("program.keys.create"), primary = true) {
                activate("program.keys.create")
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            view.keys().forEachIndexed { index, entry ->
                key(entry.id()) {
                    if (index > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp)
                        .background(palette.separator.copy(alpha = .35f)))
                    KeyRow(entry, narrow, ::l, text, enabled, activate)
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    entry: ProgramKeyRow,
    narrow: Boolean,
    label: (String) -> String,
    text: (TextToken) -> String,
    enabled: (String) -> Boolean,
    activate: (String) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val dates = remember { java.text.DateFormat.getDateTimeInstance() }
    val sources = if (entry.any()) label("source.any") else entry.networks().joinToString(", ")
    val used = if (entry.lastUsedAt() > 0) dates.format(java.util.Date(entry.lastUsedAt())) else label("unused")
    @Composable fun actions() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecurityButton(label("edit"), entry.editAction(), enabled(entry.editAction())) { activate(entry.editAction()) }
            SecurityButton(label("revoke"), entry.revokeAction(), enabled(entry.revokeAction()), destructive = true) {
                activate(entry.revokeAction())
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("program.keys.row"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ToolIcon(Icons.Default.Key)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                CupertinoText(entry.name(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                TransportChip(entry.transport(), label("transport.state." + entry.transport()))
            }
            CupertinoText(label("summary").replace("{count}", entry.count().toString()) + " · " + sources,
                fontSize = 12.sp, color = palette.secondaryText)
            CupertinoText(label("times").replace("{created}", dates.format(java.util.Date(entry.createdAt())))
                .replace("{used}", used), fontSize = 12.sp, color = palette.secondaryText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextLink(text(entry.transportLabel()), entry.transportAction(), enabled(entry.transportAction())) {
                    activate(entry.transportAction())
                }
                TextLink(label("instructions.title"), entry.promptAction(), enabled(entry.promptAction())) {
                    activate(entry.promptAction())
                }
            }
            if (narrow) actions()
        }
        if (!narrow) actions()
    }
}

/** 行内文字链接：保留键盘焦点轮廓与悬停下划线，不占用按钮高度。 */
@Composable
private fun TextLink(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val shape = RoundedCornerShape(4.dp)
    CupertinoText(
        label,
        Modifier.testTag(tag).clip(shape)
            .border(2.dp, if (focused && keyboard) palette.link else Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = if (enabled) palette.link else palette.secondaryText,
        textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
    )
}

@Composable
private fun TransportChip(state: String, label: String) {
    val palette = LocalExperiencePalette.current
    val (color, surface) = when (state) {
        "HTTP_ALLOWED" -> palette.warning to palette.warningSurface
        "REVIEWED" -> palette.secondaryText to palette.secondarySurface
        else -> palette.success to palette.successSurface
    }
    Box(Modifier.background(surface, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp)) {
        CupertinoText(label, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun KeySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CupertinoText(title, Modifier.semantics { heading() }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun KeyEditor(
    view: ProgramKeysView,
    label: (String, Array<out Any>) -> String,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    activate: (String) -> Unit,
    enabled: (String) -> Boolean,
) {
    val palette = LocalExperiencePalette.current
    fun l(key: String, vararg args: Any) = label(key, args)
    val inputs = view.inputs()
    val any = view.anySource()
    KeySection(l("name")) {
        key(inputs[0].id(), inputs[0].stateRevision()) {
            ComposeDesktopUiNodeRenderer.TextInput(inputs[0], text, emit,
                Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(inputs[0].id()), includeLabel = false)
        }
    }
    KeySection(l("source")) {
        @Composable fun option(title: String, value: Boolean, tag: String) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag(tag)
                    .selectable(any.selected() == value, enabled = any.enabled(), role = Role.RadioButton) {
                        emit(Event(EventType.CHANGE, any.id(), Value.bool(value)))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopRadioButton(any.selected() == value, enabled = any.enabled())
                CupertinoText(title, fontSize = 14.sp)
            }
        }
        Column {
            option(l("source.any"), true, "program.keys.source.any")
            option(l("source.restricted"), false, "program.keys.source.restricted")
        }
        if (!any.selected()) Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            key(inputs[1].id(), inputs[1].stateRevision()) {
                ComposeDesktopUiNodeRenderer.TextInput(inputs[1], text, emit,
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(inputs[1].id()))
            }
            CupertinoText(l("source.help"), fontSize = 12.sp, color = palette.secondaryText)
        }
        DesktopNotice(l("transport.advice"))
    }
    KeySection(l("permissions")) {
        CupertinoText(l("permissions.help"), fontSize = 12.sp, color = palette.secondaryText)
        Column(Modifier.fillMaxWidth()) {
            view.groups().forEachIndexed { index, group ->
                key(group.actionId()) {
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
                    PermissionGroup(group, ::l, text, emit, activate, enabled)
                }
            }
        }
    }
}

@Composable
private fun PermissionGroup(
    group: ProgramPermissionGroup,
    label: (String, Array<out Any>) -> String,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    activate: (String) -> Unit,
    enabled: (String) -> Boolean,
) {
    val palette = LocalExperiencePalette.current
    var expanded by remember { mutableStateOf(group.permissions().any { it.selected() }) }
    val selected = group.permissions().count { it.selected() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).testTag(group.actionId() + ".expand")
                    .toggleable(expanded, role = Role.Button) { expanded = it }
                    .semantics { stateDescription = label("permissions.selected", arrayOf(selected, group.permissions().size)) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DesktopIcon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null,
                    Modifier.size(18.dp), palette.secondaryText)
                CupertinoText(text(group.label()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                CupertinoText(label("permissions.selected", arrayOf(selected, group.permissions().size)),
                    fontSize = 12.sp, color = palette.secondaryText)
            }
            TextLink(label("select.current", emptyArray()), group.actionId(), enabled(group.actionId())) {
                activate(group.actionId())
                expanded = true
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 30.dp, bottom = 6.dp)) {
                group.permissions().forEach { permission ->
                    key(permission.id()) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag(permission.id())
                                .toggleable(permission.selected(), enabled = permission.enabled(), role = Role.Checkbox) {
                                    emit(Event(EventType.CHANGE, permission.id(), Value.bool(it)))
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CupertinoCheckBox(permission.selected(), null, enabled = permission.enabled())
                            CupertinoText(text(permission.label()), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyReview(
    view: ProgramKeysView,
    narrow: Boolean,
    label: (String, Array<out Any>) -> String,
    text: (TextToken) -> String,
) {
    val palette = LocalExperiencePalette.current
    fun l(key: String) = label(key, emptyArray())
    @Composable fun summary(title: String, content: @Composable ColumnScope.() -> Unit) {
        if (narrow) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CupertinoText(title, fontSize = 12.sp, color = palette.secondaryText)
            content()
        } else Row {
            CupertinoText(title, Modifier.width(140.dp), fontSize = 13.sp, color = palette.secondaryText)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
    val inputs = view.inputs()
    Column(Modifier.fillMaxWidth().testTag("program.keys.review"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        summary(l("name")) { CupertinoText(inputs[0].value(), fontSize = 14.sp) }
        summary(l("source")) {
            if (view.anySource().selected()) CupertinoText(l("source.any"), fontSize = 14.sp)
            else inputs[1].value().split('\n', ',').map(String::trim).filter(String::isNotEmpty).forEach {
                CupertinoText(it, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        summary(l("permissions")) {
            val groups = view.groups().map { it to it.permissions().filter(Toggle::selected) }.filter { it.second.isNotEmpty() }
            if (groups.isEmpty()) DesktopNotice(l("permissions.none"), TextStyle.WARNING)
            groups.forEach { (group, permissions) ->
                Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CupertinoText(text(group.label()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    permissions.forEach { permission ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesktopIcon(Icons.Default.Check, null, Modifier.size(14.dp), palette.link)
                            CupertinoText(text(permission.label()), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatedKey(view: ProgramKeysView, label: (String, Array<out Any>) -> String, text: (TextToken) -> String, copyEnabled: Boolean, copy: () -> Unit) {
    val palette = LocalExperiencePalette.current
    fun l(key: String) = label(key, emptyArray())
    var promptFeedback by remember(view.agentPrompt()) { mutableStateOf<String?>(null) }
    val keyFeedback = view.notice()?.key()?.takeIf { promptFeedback == null && (it == "copied" || it == "copy.manual") }
    KeySection(l("created.secret")) {
        CupertinoText(l("created.help"), fontSize = 13.sp, color = palette.secondaryText)
        // 复制按钮嵌在展示框右侧；文字仍可选中，自动复制失败时可手动复制。
        val copied = keyFeedback == "copied"
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth()
                .background(palette.secondarySurface, RoundedCornerShape(10.dp))
                .border(1.dp, palette.controlBorder.copy(alpha = .5f), RoundedCornerShape(10.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(Modifier.weight(1f).padding(vertical = 8.dp)) {
                CupertinoText(view.secret(), Modifier.testTag("program.keys.secret"),
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
            CupertinoIconButton(
                onClick = { promptFeedback = null; copy() },
                enabled = copyEnabled,
                modifier = Modifier.testTag("program.keys.copy"),
            ) {
                DesktopIcon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, l("copy"), Modifier.size(20.dp),
                    tint = if (copied) palette.success else palette.link)
            }
        }
        keyFeedback?.let {
            CupertinoText(l(it), Modifier.testTag("program.keys.copy.notice").semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 13.sp, color = if (copied) palette.success else palette.secondaryText)
        }
    }
    if (view.selfUrl().isNotEmpty()) KeySection(l("verify.title")) {
        CupertinoText(l("verify.help"), fontSize = 13.sp, color = palette.secondaryText)
        SelectionContainer {
            CupertinoText(verificationCommands(view.selfUrl()), Modifier.widthIn(max = 760.dp).testTag("program.keys.verify"),
                fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
        }
    }
    AgentPromptSection(view, ::l, text, promptFeedback) { promptFeedback = it }
}

/** 为已有密钥重新生成给 Agent 的提示词；提示词不含秘密。 */
@Composable
private fun PromptKey(view: ProgramKeysView, label: (String, Array<out Any>) -> String, text: (TextToken) -> String) {
    val palette = LocalExperiencePalette.current
    fun l(key: String) = label(key, emptyArray())
    var feedback by remember(view.agentPrompt()) { mutableStateOf<String?>(null) }
    if (view.targetName().isNotEmpty()) CupertinoText(view.targetName(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    CupertinoText(l("instructions.help"), fontSize = 13.sp, color = palette.secondaryText)
    AgentPromptSection(view, ::l, text, feedback) { feedback = it }
}

@Composable
private fun AgentPromptSection(
    view: ProgramKeysView,
    l: (String) -> String,
    text: (TextToken) -> String,
    feedback: String?,
    onFeedback: (String?) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    KeySection(l("instructions.title")) {
        val prompt = view.agentPrompt()
        if (prompt == null) {
            CupertinoText(l("instructions.unavailable"), fontSize = 13.sp, color = palette.secondaryText)
            return@KeySection
        }
        val instructions = text(prompt)
        SelectionContainer {
            CupertinoText(instructions, Modifier.widthIn(max = 760.dp).testTag("program.keys.instructions"),
                fontSize = 14.sp, lineHeight = 21.sp)
        }
        val clipboard = LocalClipboardManager.current
        val copied = feedback == "instructions.copied"
        SecurityButton(l(if (copied) "instructions.copied" else "copy.instructions"),
            "program.keys.copy-instructions", true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            onFeedback(try {
                clipboard.setText(AnnotatedString(instructions))
                "instructions.copied"
            } catch (_: Exception) {
                "instructions.manual"
            })
        }
        if (feedback == "instructions.manual") DesktopNotice(l("instructions.manual"), TextStyle.WARNING)
    }
}

/** 环境变量占位只出现在命令里，完整密钥不会被拼进可复制的文本。 */
internal fun verificationCommands(selfUrl: String): String =
    "curl -H \"Authorization: Bearer \$PIXIV_PROGRAM_KEY\" $selfUrl\n" +
        "curl.exe -H \"Authorization: Bearer \$env:PIXIV_PROGRAM_KEY\" $selfUrl"
