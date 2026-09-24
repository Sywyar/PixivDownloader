@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.DesktopLinearProgress
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

private const val INPUT_IDLE_MILLIS = 500L
private enum class Feedback { NONE, INVALID, CAUTION, VALID }

/** 逐项展开的账户表单；输入法组合态不参与停顿判断，密码不进入可恢复状态。 */
@Composable
internal fun OnboardingAccountForm(
    node: DesktopUiNode.AccountSetup,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    fun message(key: String, vararg args: Any): String = text(DesktopUiNode.TextToken(
        "gui-compose",
        "gui.compose.onboarding.account.$key",
        "",
        args.map(Any::toString),
    ))
    var username by remember(node.id()) { mutableStateOf(TextFieldValue(node.username().value())) }
    var password by remember(node.id(), node.password().stateRevision()) { mutableStateOf(TextFieldValue()) }
    var passwordVisible by remember(node.id(), node.password().stateRevision()) { mutableStateOf(false) }
    var expanded by remember(node.id()) { mutableStateOf(false) }
    var usernameChecked by remember(node.id()) { mutableStateOf(false) }
    var passwordFeedback by remember(node.id(), node.password().stateRevision()) { mutableStateOf(Feedback.NONE) }
    var finishRevealed by remember(node.id(), node.password().stateRevision()) { mutableStateOf(false) }
    var passwordEdit by remember(node.id(), node.password().stateRevision()) { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val canFinish = username.text.isNotBlank() && username.composition == null &&
        password.composition == null && passwordFeedback in setOf(Feedback.CAUTION, Feedback.VALID)
    val entry by animateFloatAsState(if (entered) 1f else 0f, tween(280), label = "account-entry")

    LaunchedEffect(node.id()) {
        entered = true
        usernameFocus.requestFocus()
    }
    LaunchedEffect(node.username().value()) {
        if (username.text != node.username().value()) {
            username = TextFieldValue(node.username().value(), TextRange(node.username().value().length))
        }
    }
    LaunchedEffect(username.text, username.composition, node.username().enabled()) {
        usernameChecked = false
        if (!node.username().enabled() || username.composition != null) return@LaunchedEffect
        if (username.text.isBlank() && !expanded) return@LaunchedEffect
        delay(INPUT_IDLE_MILLIS)
        usernameChecked = true
        if (username.text.isNotBlank()) expanded = true
    }
    LaunchedEffect(password.text, password.composition, node.password().enabled()) {
        if (!node.password().enabled() || password.composition != null || passwordEdit == 0) {
            return@LaunchedEffect
        }
        delay(INPUT_IDLE_MILLIS)
        passwordFeedback = when {
            password.text.length < node.minimumPasswordLength() -> Feedback.INVALID
            password.text.length < node.recommendedPasswordLength() -> Feedback.CAUTION
            else -> Feedback.VALID
        }
        if (passwordFeedback != Feedback.INVALID) finishRevealed = true
    }
    fun update(input: DesktopUiNode.TextInput, value: TextFieldValue, previous: TextFieldValue) {
        if (value.text != previous.text) {
            emit(DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE, input.id(), DesktopUiNode.Value.text(value.text)))
        }
    }
    val passwordMessage = when (passwordFeedback) {
        Feedback.INVALID -> message("too-short", node.minimumPasswordLength())
        Feedback.CAUTION -> message("recommend", node.recommendedPasswordLength())
        else -> ""
    }
    val usernameMessage = if (usernameChecked && username.text.isBlank()) message("username-required") else ""
    val notice = when {
        node.confirmWeakPassword() -> message("confirm-weak", node.recommendedPasswordLength())
        else -> node.notice()?.let { text(it.text()) }.orEmpty()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                    .testTag("onboarding.account.form")
                    .graphicsLayer {
                        alpha = entry
                        scaleX = .98f + .02f * entry
                        scaleY = scaleX
                    },
            ) {
                AccountInput(
                    node = node.username(),
                    label = text(node.username().label()),
                    value = username,
                    onValueChange = { next ->
                        val previous = username
                        username = next
                        if (next.text != previous.text || next.composition != previous.composition) usernameChecked = false
                        update(node.username(), next, previous)
                    },
                    feedback = when {
                        usernameMessage.isNotBlank() -> Feedback.INVALID
                        usernameChecked -> Feedback.VALID
                        else -> Feedback.NONE
                    },
                    errorMessage = usernameMessage,
                    focusRequester = usernameFocus,
                    onNext = {
                        if (username.text.isNotBlank() && username.composition == null) {
                            expanded = true
                            scope.launch {
                                withFrameNanos { }
                                passwordFocus.requestFocus()
                            }
                        }
                    },
                )
                ExpandingContent(usernameMessage.isNotEmpty()) {
                    FeedbackText(usernameMessage, Feedback.INVALID)
                }
                ExpandingContent(expanded) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        AccountInput(
                            node = node.password(),
                            label = text(node.password().label()),
                            value = password,
                            onValueChange = { next ->
                                val previous = password
                                password = next
                                if (next.text != previous.text || next.composition != previous.composition) {
                                    passwordFeedback = Feedback.NONE
                                    passwordEdit++
                                }
                                update(node.password(), next, previous)
                            },
                            feedback = passwordFeedback,
                            errorMessage = passwordMessage,
                            focusRequester = passwordFocus,
                            password = true,
                            passwordVisible = passwordVisible,
                            onTogglePassword = { passwordVisible = !passwordVisible },
                            visibilityLabel = message(if (passwordVisible) "hide-password" else "show-password"),
                            editRevision = passwordEdit,
                        )
                        ExpandingContent(passwordMessage.isNotBlank()) {
                            AnimatedContent(
                                targetState = passwordMessage to passwordFeedback,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                                label = "password-feedback",
                            ) { (message, feedback) -> FeedbackText(message, feedback) }
                        }
                    }
                }
                ExpandingContent(notice.isNotBlank()) {
                    AnimatedContent(
                        targetState = notice,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                        label = "account-notice",
                    ) { value ->
                        FeedbackText(value, if (node.confirmWeakPassword()) Feedback.CAUTION else Feedback.INVALID)
                    }
                }
                ExpandingContent(finishRevealed) {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        CupertinoButton(
                            onClick = {
                                if (canFinish && node.submit().enabled()) {
                                    emit(DesktopUiNode.Event(
                                        DesktopUiNode.EventType.ACTIVATE,
                                        node.submit().id(),
                                        DesktopUiNode.Value.empty(),
                                    ))
                                }
                            },
                            enabled = canFinish && node.submit().enabled(),
                            colors = CupertinoButtonDefaults.filledButtonColors(contentColor = palette.onAccent),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(node.submit().id()),
                        ) {
                            AnimatedContent(
                                targetState = node.submitting(),
                                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                                label = "account-submit",
                            ) { submitting ->
                                CupertinoText(
                                    if (submitting) message("submitting") else text(node.submit().label()),
                                    fontSize = 15.sp,
                                )
                            }
                        }
                        ExpandingContent(node.submitting()) {
                            DesktopLinearProgress(null, Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandingContent(visible: Boolean, content: @Composable () -> Unit) {
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
private fun FeedbackText(message: String, feedback: Feedback) {
    val palette = LocalExperiencePalette.current
    CupertinoText(
        message,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (feedback == Feedback.CAUTION) palette.warning else palette.error,
        style = CupertinoTheme.typography.footnote,
    )
}

@Composable
private fun AccountInput(
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
            textStyle = CupertinoTheme.typography.body.copy(fontSize = 15.sp, lineHeight = 20.sp, color = palette.text),
            cursorBrush = SolidColor(palette.link),
            visualTransformation = if (password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = if (password) ImeAction.Default else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(node.id())
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
