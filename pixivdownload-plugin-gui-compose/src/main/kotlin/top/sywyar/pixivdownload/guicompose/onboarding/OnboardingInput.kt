@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

internal const val INPUT_IDLE_MILLIS = 500L
internal enum class Feedback { NONE, INVALID, CAUTION, VALID }

@Composable
internal fun ExpandingContent(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        // 只裁剪展开方向，给错误状态的横向抖动保留绘制空间。
        modifier = Modifier.drawWithContent {
            clipRect(left = -size.width, right = size.width * 2) { this@drawWithContent.drawContent() }
        },
        enter = expandVertically(tween(360, easing = FastOutSlowInEasing), expandFrom = Alignment.Top, clip = false) +
            fadeIn(tween(240, delayMillis = 70)),
        exit = shrinkVertically(tween(240, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top, clip = false) +
            fadeOut(tween(140)),
    ) { content() }
}

@Composable
internal fun FeedbackText(message: String, feedback: Feedback) {
    val palette = LocalExperiencePalette.current
    CupertinoText(
        message,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (feedback == Feedback.CAUTION) palette.warning else palette.error,
        style = CupertinoTheme.typography.footnote,
    )
}

@Composable
internal fun OnboardingInput(
    node: DesktopUiNode.TextInput,
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    feedback: Feedback,
    errorMessage: String,
    focusRequester: FocusRequester,
    onNext: () -> Unit = {},
    password: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
    visibilityLabel: String = "",
    editRevision: Int = 0,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tint by animateColorAsState(
        when (feedback) {
            Feedback.INVALID -> palette.error
            Feedback.CAUTION -> palette.warning
            Feedback.VALID -> palette.success
            Feedback.NONE -> if (focused) palette.link else palette.controlBorder
        },
        tween(180),
        label = "account-field-tint",
    )
    val background by animateColorAsState(
        if (feedback == Feedback.INVALID) palette.errorSurface else palette.surface,
        tween(180),
        label = "account-field-background",
    )
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(feedback, editRevision) {
        if (feedback == Feedback.INVALID && coroutineContext[MotionDurationScale]?.scaleFactor != 0f) {
            shake.animateTo(0f, keyframes {
                durationMillis = 420
                0f at 0
                -14f at 45
                12f at 100
                -9f at 160
                6f at 225
                -3f at 295
                0f at 420
            })
        } else {
            shake.animateTo(0f, tween(100))
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CupertinoText(
            label,
            color = palette.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = node.enabled(),
            singleLine = true,
            interactionSource = interaction,
            textStyle = CupertinoTheme.typography.body.copy(fontSize = 15.sp, lineHeight = 20.sp, color = if (node.enabled()) palette.text else palette.secondaryText),
            cursorBrush = SolidColor(palette.link),
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    password -> KeyboardType.Password
                    node.inputKind() == DesktopUiNode.InputKind.NUMBER -> KeyboardType.Number
                    else -> KeyboardType.Text
                },
                imeAction = if (password) ImeAction.Default else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocusChanged(it.isFocused) }.testTag(node.id())
                .semantics {
                    contentDescription = label
                    if (feedback == Feedback.INVALID && errorMessage.isNotBlank()) error(errorMessage)
                }
                .graphicsLayer { translationX = with(density) { shake.value.dp.toPx() } }
                .border(if (focused || feedback != Feedback.NONE) 2.dp else 1.dp, tint, shape)
                .background(background, shape),
            decorationBox = { input ->
                Row(
                    Modifier.heightIn(min = 42.dp).padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) { input() }
                    AnimatedContent(
                        targetState = feedback,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                        label = "account-field-mark",
                    ) { state ->
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            when (state) {
                                Feedback.VALID -> DesktopIcon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint)
                                Feedback.INVALID -> DesktopIcon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint)
                                Feedback.CAUTION -> DesktopIcon(Icons.Default.WarningAmber, null, Modifier.size(18.dp), tint)
                                Feedback.NONE -> Unit
                            }
                        }
                    }
                    if (password) CupertinoButton(
                        onClick = onTogglePassword,
                        enabled = node.enabled(),
                        colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.secondaryText),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.size(40.dp).semantics { contentDescription = visibilityLabel },
                    ) {
                        DesktopIcon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null,
                            Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
    }
}
