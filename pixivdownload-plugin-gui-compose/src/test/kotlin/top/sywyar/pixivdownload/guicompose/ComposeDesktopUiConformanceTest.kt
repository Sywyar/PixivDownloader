package top.sywyar.pixivdownload.guicompose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiModel
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 桌面真实渲染闭环")
class ComposeDesktopUiConformanceTest {
    @Test
    @DisplayName("真实 Compose runtime 驱动共享场景且不重读最新修订号")
    fun drivesSharedScenariosOnComposeRuntime() {
        scenarios().forEach { scenario ->
            if (scenario.id == "password-generation") runPasswordScenario(scenario)
            else runScenario(scenario)
        }
    }

    private fun runScenario(scenario: Scenario) = runComposeUiTest {
        val node = node(scenario.id, 1)
        val model = TestModel(node)
        val context = context(model)
        val observed = context.currentSnapshot()
        val dispatch: (DesktopUiNode.Event) -> Unit = { context.dispatchEvent(observed, it) }
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(
                    node,
                    { it.fallback() },
                    dispatch,
                )
            }
        }

        when (scenario.id) {
            "burst-text" -> scenario.operations.forEach {
                onNode(hasSetTextAction()).performTextInput(it)
            }
            "number-spinner" -> scenario.operations.forEach {
                onNodeWithText(if (it == "+") "+" else "−").performClick()
            }
            "number-slider" -> scenario.operations.forEach { value ->
                onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
            }
            "toggle-burst" -> scenario.operations.forEach {
                onNode(isToggleable()).performClick()
            }
            "choice-single" -> {
                onNodeWithText("…").performClick()
                onNodeWithText(label(scenario.operations.single())).performClick()
            }
            "choice-multiple" -> scenario.operations.forEach {
                onAllNodes(isToggleable())[optionIndex(it)].performClick()
            }
            "table-multiple" -> scenario.operations.forEach {
                onNodeWithText(label(it)).performClick()
            }
            "tree-multiple" -> {
                assertEquals(0, onAllNodesWithText("Child").fetchSemanticsNodes().size)
                onNodeWithText("▸").performClick()
                assertEquals(1, onAllNodesWithText("Child").fetchSemanticsNodes().size)
                scenario.operations.forEach { onNodeWithText(label(it)).performClick() }
            }
            "stale-action" -> scenario.operations.forEach {
                onNodeWithText("Run").performClick()
            }
            else -> error("unknown desktop UI scenario: ${scenario.id}")
        }

        waitForIdle()
        assertEquals(scenario.expected, model.acceptedValues(), scenario.id)
    }

    private fun runPasswordScenario(scenario: Scenario) = runComposeUiTest {
        val firstNode = node(scenario.id, 1)
        val model = TestModel(firstNode)
        val context = context(model)
        val observed = context.currentSnapshot()
        var renderedNode by mutableStateOf(firstNode)
        var documentRevision by mutableStateOf(observed.revision())
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(
                    renderedNode,
                    { it.fallback() },
                    { context.dispatchEvent(observed, it) },
                    documentRevision = documentRevision,
                )
            }
        }

        onNode(hasSetTextAction()).performTextInput(scenario.operations[0])
        runOnIdle {
            model.publish(firstNode)
            documentRevision = model.snapshot().revision()
        }
        onNode(hasSetTextAction()).performTextInput(scenario.operations[2])

        val clearedNode = node(scenario.id, 2)
        runOnIdle {
            model.publish(clearedNode)
            renderedNode = clearedNode
            documentRevision = model.snapshot().revision()
        }
        onNode(hasSetTextAction()).performTextInput(scenario.operations[4])

        waitForIdle()
        assertEquals(scenario.expected, model.acceptedValues(), scenario.id)
    }

    private fun context(model: TestModel): DesktopUiContext {
        val provider = GuiComposePlugin()
        return DesktopUiContext(
            false,
            "Conformance",
            model,
            { it.fallback() },
            {},
            { "system" },
            provider.id(),
            provider.supportedNodeKinds(),
            provider.supportedCapabilities(),
        )
    }

    private fun stamp(
        observed: DesktopUiSnapshot,
        event: DesktopUiNode.Event,
    ): DesktopUiNode.Event = if (event.type() == DesktopUiNode.EventType.ACTIVATE) {
        event.atRevision(observed.revision())
    } else {
        event.atRevisions(
            observed.revision(),
            checkNotNull(observed.interactionRevisions()[event.nodeId()]),
        )
    }

    private fun node(id: String, stateRevision: Long): DesktopUiNode = when (id) {
        "burst-text" -> DesktopUiNode.TextInput(
            id, "$id.value", raw(label(id)), null, DesktopUiNode.InputKind.TEXT, "", 12, 1, true,
        )
        "password-generation" -> DesktopUiNode.TextInput(
            id, "$id.value", raw(label(id)), null, DesktopUiNode.InputKind.PASSWORD, "", 12, 1, true,
            stateRevision,
        )
        "number-spinner" -> DesktopUiNode.NumberInput(
            id, "$id.value", raw(label(id)), null, DesktopUiNode.NumberStyle.SPINNER, 1, 0, 5, 1, true,
        )
        "number-slider" -> DesktopUiNode.NumberInput(
            id, "$id.value", raw(label(id)), null, DesktopUiNode.NumberStyle.SLIDER, 0, 0, 5, 1, true,
        )
        "toggle-burst" -> DesktopUiNode.Toggle(
            id, "$id.value", raw(label(id)), null, DesktopUiNode.ToggleStyle.SWITCH, false, true,
        )
        "choice-single" -> choice(
            id, DesktopUiNode.ChoiceStyle.COMBO_BOX, DesktopUiNode.SelectionMode.SINGLE,
        )
        "choice-multiple" -> choice(
            id, DesktopUiNode.ChoiceStyle.CHECK_BOXES, DesktopUiNode.SelectionMode.MULTIPLE,
        )
        "table-multiple" -> DesktopUiNode.Table(
            id,
            "$id.value",
            listOf(DesktopUiNode.TableColumn("value", raw("Value"), 100)),
            rows(),
            DesktopUiNode.SelectionMode.MULTIPLE,
            emptyList(),
            true,
        )
        "tree-multiple" -> DesktopUiNode.Tree(
            id,
            "$id.value",
            listOf(
                DesktopUiNode.TreeItem("one", raw("One"), listOf(
                    DesktopUiNode.TreeItem("child", raw("Child"), emptyList()),
                )),
                DesktopUiNode.TreeItem("three", raw("Three"), emptyList()),
            ),
            DesktopUiNode.SelectionMode.MULTIPLE,
            emptyList(),
            true,
        )
        "stale-action" -> DesktopUiNode.Button(
            id, "$id.action", raw("Run"), null, DesktopUiNode.ButtonStyle.PRIMARY, true,
        )
        else -> error("unknown desktop UI scenario: $id")
    }

    private fun choice(
        id: String,
        style: DesktopUiNode.ChoiceStyle,
        mode: DesktopUiNode.SelectionMode,
    ) = DesktopUiNode.Choice(
        id,
        "$id.value",
        raw(label(id)),
        null,
        style,
        mode,
        listOf(
            DesktopUiNode.Option("one", raw("One"), true),
            DesktopUiNode.Option("two", raw("Two"), true),
            DesktopUiNode.Option("three", raw("Three"), true),
        ),
        emptyList(),
        true,
    )

    private fun rows() = listOf(
        DesktopUiNode.TableRow("one", listOf("One")),
        DesktopUiNode.TableRow("two", listOf("Two")),
        DesktopUiNode.TableRow("three", listOf("Three")),
    )

    private fun scenarios(): List<Scenario> {
        val lines = Files.readAllLines(fixture(), StandardCharsets.UTF_8)
        check(lines.firstOrNull() == "id\toperations\texpected") {
            "invalid desktop UI conformance header"
        }
        return lines.drop(1).map { line ->
            val fields = line.split('\t')
            check(fields.size == 3) { "invalid desktop UI conformance row: $line" }
            Scenario(fields[0], fields[1].split('|'), fields[2])
        }
    }

    private fun fixture(): Path = generateSequence(Path.of("").toAbsolutePath(), Path::getParent)
        .map { it.resolve("test-fixtures/desktop-ui/conformance-cases.tsv") }
        .firstOrNull(Files::isRegularFile)
        ?: error("desktop UI conformance fixture not found")

    private fun optionIndex(id: String): Int = when (id) {
        "one" -> 0
        "two" -> 1
        "three" -> 2
        else -> error("unknown option: $id")
    }

    private fun label(id: String): String = id.replaceFirstChar(Char::uppercase)
    private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)

    private data class Scenario(val id: String, val operations: List<String>, val expected: String)

    private class TestModel(node: DesktopUiNode) : DesktopUiModel {
        private var current = snapshot(1, node)
        private val accepted = mutableListOf<DesktopUiNode.Event>()

        @Synchronized
        override fun snapshot(): DesktopUiSnapshot = current

        @Synchronized
        override fun dispatch(event: DesktopUiNode.Event) {
            val isCurrent = if (event.type() == DesktopUiNode.EventType.ACTIVATE) {
                event.documentRevision() == current.revision()
            } else {
                current.interactionRevisions()[event.nodeId()] == event.interactionRevision()
            }
            if (!isCurrent) return
            accepted += event
            current = DesktopUiSnapshot(
                current.revision() + 1,
                current.document(),
                current.interactionRevisions(),
            )
        }

        @Synchronized
        fun publish(node: DesktopUiNode) {
            current = snapshot(current.revision() + 1, node)
        }

        @Synchronized
        fun acceptedValues(): String = accepted.joinToString(">") { event ->
            if (event.type() == DesktopUiNode.EventType.ACTIVATE) "activate"
            else event.value().values().joinToString(",")
        }

        companion object {
            private fun snapshot(revision: Long, node: DesktopUiNode): DesktopUiSnapshot {
                val document = DesktopUiDocument(
                    listOf(DesktopUiDocument.Page("page", DesktopUiNode.TextToken.raw("Page"), node)),
                )
                return DesktopUiSnapshot(revision, document, mapOf(node.id() to 1L))
            }
        }
    }
}
