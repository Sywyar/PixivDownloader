@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.OnboardingProxySettings

@Composable
internal fun OnboardingProxyForm(
    node: OnboardingProxySettings,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val hostFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .background(palette.secondarySurface, RoundedCornerShape(12.dp)).padding(16.dp)
            .testTag("hub.proxy.settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CupertinoText(text(node.enabled().label()), fontSize = 14.sp, modifier = Modifier.weight(1f))
            CupertinoSwitch(
                checked = node.enabled().selected(),
                onCheckedChange = {
                    emit(DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        node.enabled().id(),
                        DesktopUiNode.Value.bool(it),
                    ))
                },
                enabled = node.enabled().enabled(),
                colors = CupertinoSwitchDefaults.colors(
                    thumbColor = palette.onAccent,
                    checkedTrackColor = palette.link,
                    uncheckedTrackColor = palette.controlBorder,
                ),
                modifier = Modifier.padding(start = 12.dp).testTag(node.enabled().id())
                    .semantics { contentDescription = text(node.enabled().label()) },
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            @Composable fun host(modifier: Modifier) = ProxyInput(
                node.host(),
                node.validationAttempt(),
                text,
                emit,
                OnboardingProxySettings::validHost,
                hubToken("network.invalid-host"),
                hostFocus,
                true,
                { portFocus.requestFocus() },
                modifier,
            )
            @Composable fun port(modifier: Modifier) = ProxyInput(
                node.port(),
                node.validationAttempt(),
                text,
                emit,
                OnboardingProxySettings::validPort,
                DesktopUiNode.TextToken.key("gui.welcome.proxy.invalid.port"),
                portFocus,
                OnboardingProxySettings.validHost(node.host().value()),
                {},
                modifier,
            )
            if (maxWidth < 360.dp * LocalDensity.current.fontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    host(Modifier.fillMaxWidth())
                    port(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    host(Modifier.weight(1f))
                    port(Modifier.width(132.dp * LocalDensity.current.fontScale))
                }
            }
        }
    }
}

@Composable
private fun ProxyInput(
    node: DesktopUiNode.TextInput,
    validationAttempt: Int,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    valid: (String) -> Boolean,
    errorToken: DesktopUiNode.TextToken,
    focus: FocusRequester,
    firstInvalid: Boolean,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    var value by rememberSaveable(node.id(), stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(node.value()))
    }
    var edited by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val intoView = remember { BringIntoViewRequester() }
    LaunchedEffect(node.value()) {
        if (value.text != node.value()) value = TextFieldValue(node.value(), TextRange(node.value().length))
    }
    LaunchedEffect(value.text, value.composition, node.enabled()) {
        checked = false
        if (!node.enabled() || value.composition != null || !edited) return@LaunchedEffect
        delay(INPUT_IDLE_MILLIS)
        checked = true
    }
    LaunchedEffect(validationAttempt, node.enabled()) {
        if (validationAttempt > 0 && node.enabled()) {
            checked = true
            if (!valid(value.text) && firstInvalid) {
                withFrameNanos { }
                focus.requestFocus()
                intoView.bringIntoView()
            }
        }
    }
    val feedback = when {
        !node.enabled() || !checked || value.composition != null -> Feedback.NONE
        valid(value.text) -> Feedback.VALID
        else -> Feedback.INVALID
    }
    val error = if (feedback == Feedback.INVALID) text(errorToken) else ""
    Column(modifier.bringIntoViewRequester(intoView)) {
        OnboardingInput(
            node = node,
            label = text(node.label()),
            value = value,
            onValueChange = { next ->
                if (next.text != value.text || next.composition != value.composition) {
                    edited = true
                    checked = false
                }
                if (next.text != value.text) emit(DesktopUiNode.Event(
                    DesktopUiNode.EventType.CHANGE,
                    node.id(),
                    DesktopUiNode.Value.text(next.text),
                ))
                value = next
            },
            feedback = feedback,
            errorMessage = error,
            focusRequester = focus,
            onNext = onNext,
            editRevision = validationAttempt,
        )
        ExpandingContent(error.isNotEmpty()) { FeedbackText(error, Feedback.INVALID) }
    }
}
