@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.sywyar.pixivdownload.guicompose.DesktopLinearProgress
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

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
                OnboardingInput(
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
                        OnboardingInput(
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
