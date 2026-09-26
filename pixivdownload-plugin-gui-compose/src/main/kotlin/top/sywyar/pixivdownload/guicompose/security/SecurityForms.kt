@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.security

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.SecurityInputValidation
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.onboarding.*
import top.sywyar.pixivdownload.guicompose.tools.*

@Composable
internal fun SecurityPasswordPanel(node: SecurityOverview, text: (TextToken) -> String, emit: (Event) -> Unit) {
    val palette = LocalExperiencePalette.current
    fun label(key: String, vararg args: String) = securityText(text, key, *args)
    val names = listOf("current", "new", "confirm")
    val values = remember(node.formRevision()) { mutableStateMapOf<String, TextFieldValue>().apply { names.forEach { put(it, TextFieldValue()) } } }
    val reviewed = remember(node.formRevision()) { mutableStateMapOf<String, Boolean>() }
    val revealed = remember(node.formRevision()) { mutableStateMapOf<String, Boolean>() }
    val focuses = remember { names.associateWith { FocusRequester() } }
    var attempt by remember { mutableIntStateOf(0) }
    fun activate(name: String) = emit(Event(EventType.ACTIVATE, "security.$name", Value.empty()))
    val errors = SecurityInputValidation.passwordErrors(values.getValue("current").text, values.getValue("new").text,
        values.getValue("confirm").text, node.minimumPasswordLength())
    LaunchedEffect(Unit) { focuses.getValue("current").requestFocus() }
    LaunchedEffect(values.getValue("current").text, values.getValue("new").text, values.getValue("confirm").text) {
        delay(INPUT_IDLE_MILLIS)
        names.filter { values.getValue(it).text.isNotEmpty() }.forEach { reviewed[it] = true }
    }
    ToolPanelLayout(label("password.dialog-title"), label("password.dialog-description"), Icons.Default.Key,
        { activate("close") }, label("close"), !node.busy(), "security", footer = {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecurityButton(label("cancel"), "security.cancel", !node.busy()) { activate("close") }
                SecurityButton(label(if (node.busy()) "working" else "password.action"), "security.submit",
                    node.actions().first { it.id() == "security.submit" }.enabled(), primary = true) {
                    names.forEach { reviewed[it] = true }
                    attempt++
                    if (errors.isEmpty()) activate("submit") else focuses.getValue(errors.keys.first()).requestFocus()
                }
            }
        }) {
        names.forEachIndexed { index, name ->
            val input = node.inputs().first { it.bindingId() == "security.$name" }
            val localError = errors[name]?.takeIf { reviewed[name] == true }?.let { label(it) }
            val serverError = node.notice()?.takeIf { node.errorField() == name }?.let(text)
            val error = serverError ?: localError
            var wasFocused by remember { mutableStateOf(false) }
            Column {
                OnboardingInput(
                    node = input,
                    label = label(name),
                    value = values.getValue(name),
                    onValueChange = { next ->
                        val changed = values.getValue(name).text != next.text
                        values[name] = next
                        if (changed) {
                            reviewed[name] = false
                            emit(Event(EventType.CHANGE, input.id(), Value.text(next.text)))
                        }
                    },
                    feedback = if (error != null) Feedback.INVALID else Feedback.NONE,
                    errorMessage = error.orEmpty(),
                    focusRequester = focuses.getValue(name),
                    onNext = { focuses[names.getOrNull(index + 1)]?.requestFocus() },
                    password = true,
                    passwordVisible = revealed[name] == true,
                    onTogglePassword = { revealed[name] = revealed[name] != true },
                    visibilityLabel = label(if (revealed[name] == true) "hide-password" else "show-password"),
                    editRevision = attempt,
                    onFocusChanged = { focused ->
                        if (wasFocused && !focused) reviewed[name] = true
                        wasFocused = focused
                    },
                )
                AnimatedVisibility(error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    FeedbackText(error.orEmpty(), Feedback.INVALID)
                }
                if (name == "new") CupertinoText(label("password.hint", node.minimumPasswordLength().toString()),
                    Modifier.padding(top = 7.dp), fontSize = 11.sp, color = palette.secondaryText)
            }
        }
        ToolNotice(label("password.notice"))
        SecurityError(node.notice()?.takeIf { node.errorField().isEmpty() }?.let(text))
    }
}

@Composable
internal fun SecurityConnectionPanel(node: SecurityOverview, text: (TextToken) -> String, emit: (Event) -> Unit) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = securityText(text, key)
    fun activate(name: String) = emit(Event(EventType.ACTIVATE, "security.$name", Value.empty()))
    val inputs = node.inputs().filter { it.inputKind() != InputKind.PASSWORD }.associateBy { it.bindingId().removePrefix("security.") }
    val values = inputs.mapValues { it.value.value() } + ("https" to node.https().selected().toString())
    val errors = SecurityInputValidation.connectionErrors(values, node.keyStoreConfigured())
    var attempt by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, node.errorField()) {
        if (attempt > 0 && errors.keys.any { it == "certificate" || it == "private-key" } ||
            node.errorField() in listOf("certificate", "private-key")) advanced = true
    }
    @Composable fun field(name: String, modifier: Modifier = Modifier) {
        val error = if (node.errorField() == name) node.notice()?.let(text) else errors[name]?.takeIf { attempt > 0 }?.let { label(it) }
        ToolField(inputs.getValue(name), text, emit, modifier, error = error, attempt = attempt,
            focusError = attempt > 0 && errors.keys.firstOrNull() == name)
    }
    ToolPanelLayout(label("connection.title"), label("connection.dialog-description"), Icons.Default.Lock,
        { activate("close") }, label("close"), !node.busy(), "security", footer = {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecurityButton(label("cancel"), "security.cancel", !node.busy()) { activate("close") }
                SecurityButton(label(if (node.busy()) "working" else "save"), "security.save",
                    node.actions().first { it.id() == "security.save" }.enabled(), primary = true) {
                    attempt++
                    if (errors.isEmpty()) activate("save")
                }
            }
        }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CupertinoText(label("https"), fontSize = 14.sp)
                CupertinoText(label("https.description"), fontSize = 12.sp, color = palette.secondaryText)
            }
            CupertinoSwitch(node.https().selected(), { emit(Event(EventType.CHANGE, node.https().id(), Value.bool(it))) },
                enabled = node.https().enabled(), colors = CupertinoSwitchDefaults.colors(checkedTrackColor = palette.link),
                modifier = Modifier.testTag("security.https"))
        }
        AnimatedVisibility(node.https().selected(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ToolNotice(label(if (node.keyStoreConfigured()) "certificate.keystore" else "certificate.hint"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field("domain", Modifier.weight(1f))
            field("port", Modifier.width(100.dp))
        }
        Column {
            ToolDisclosure(label("certificate.title"), "security.certificate.expand", advanced) { advanced = !advanced }
            AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    field("certificate")
                    field("private-key")
                    CupertinoText(label("certificate.description"), fontSize = 11.sp, color = palette.secondaryText)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CupertinoText(label("running-address"), fontSize = 12.sp, color = palette.secondaryText)
            CupertinoText(node.runningAddress().ifBlank { label("running-unknown") }, fontSize = 13.sp)
        }
        ToolNotice(label("connection.notice"))
        SecurityError(node.notice()?.takeIf { node.errorField().isEmpty() }?.let(text))
    }
}
