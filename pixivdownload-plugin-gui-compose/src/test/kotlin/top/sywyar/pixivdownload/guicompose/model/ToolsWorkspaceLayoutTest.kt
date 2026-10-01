package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.tools.descendants
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.MessageFormat
import java.util.Locale
import java.util.Optional
import java.util.Properties
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ToolsWorkspaceLayoutTest {
    @TempDir lateinit var temp: Path

    @Test
    @DisplayName("首页状态推进时仍接纳界面已显示且上下文未变的分类预览点击")
    fun classifierClickSurvivesUnrelatedStatusPublication() = runComposeUiTest {
        val stored = hashMapOf("proxy.enabled" to "false", "proxy.host" to "localhost", "proxy.port" to "8080")
        classifierEventModel(stored).use { model ->
            val delayPublication = AtomicBoolean()
            var rendered by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { if (!delayPublication.get()) rendered = it }.use {
                setContent {
                    val observed = rendered
                    PixivDownloaderTheme("light") {
                        DesktopShell(observed.document(), observed.revision(), ::resolve) { model.dispatch(observed, it) }
                    }
                }
                onNodeWithText(message("desktop.ui.page.tools")).performClick()
                onNodeWithTag("tools.entry.classifier").performClick()
                waitUntilExactlyOneExists(hasContentDescription("sample-0.png"), timeoutMillis = 10_000)
                runOnIdle {
                    delayPublication.set(true)
                    val observed = rendered
                    stored["proxy.enabled"] = "true"
                    model.rebuildStatus()
                    val current = model.snapshot()
                    assertTrue(current.revision() > observed.revision())
                    assertNotEquals(observed.document().pages().first { it.id() == "home" }, current.document().pages().first { it.id() == "home" })
                    assertEquals(observed.document().pages().first { it.id() == "tools" }, current.document().pages().first { it.id() == "tools" })
                }
                onNodeWithTag("classifier.image.0.open").performClick()
                runOnIdle {
                    assertEquals("classifier.viewer", model.snapshot().document().dialogs().singleOrNull()?.id())
                }
            }
        }
    }

    @Test
    @DisplayName("换图、关闭重开、忙碌及插件换代后拒绝旧分类动作")
    fun classifierRejectsChangedActionContexts() {
        for (change in listOf("image", "workspace", "modal", "source", "busy", "busy-return")) {
            val sources = AtomicReference(listOf(classifierEventSource(1)))
            classifierEventModel(hashMapOf(), sources::get).use { model ->
                fun activate(id: String) {
                    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
                        while (model.busy() || DesktopUiEventProtocol.index(model.snapshot().document())[id]?.enabled() != true) Thread.sleep(10)
                    }
                    synchronized(model) { model.dispatch(model.snapshot(), Event(EventType.ACTIVATE, id, Value.empty())) }
                }
                activate("tools.image-classifier.open")
                awaitClassifierImage(model, "sample-0.png")
                if (change == "modal") activate("classifier.image.0.open")
                val observed = model.snapshot()
                when (change) {
                    "image" -> {
                        activate("classifier.skip")
                        awaitClassifierImage(model, "sample-1.png")
                    }
                    "workspace" -> {
                        activate("tools.active.close")
                        activate("tools.image-classifier.open")
                        awaitClassifierImage(model, "sample-0.png")
                    }
                    "modal" -> {
                        activate("classifier.viewer.close")
                        activate("classifier.image.0.open")
                    }
                    "source" -> {
                        sources.set(listOf(classifierEventSource(2)))
                        model.rebuildStatus()
                    }
                    "busy", "busy-return" -> {
                        model.setBusy(true)
                        model.rebuildStatus()
                        if (change == "busy-return") {
                            model.setBusy(false)
                            model.rebuildStatus()
                        }
                    }
                }
                synchronized(model) {
                    val beforeClick = model.snapshot()
                    assertTrue(beforeClick.revision() > observed.revision(), change)
                    model.dispatch(observed, Event(EventType.ACTIVATE,
                        if (change == "modal") "classifier.viewer.close" else "classifier.image.0.open", Value.empty()))
                    assertSame(beforeClick, model.snapshot(), "Old action must not execute after $change")
                    assertEquals(if (change == "modal") 1 else 0, model.snapshot().document().dialogs().size, change)
                }
            }
        }
    }

    private fun classifierEventSource(generation: Long) = DesktopUiPluginSnapshot(
        "sample", false, "sample", generation, false, null, "",
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    )

    private fun classifierEventModel(
        stored: HashMap<String, String>,
        sources: () -> List<DesktopUiPluginSnapshot> = { emptyList() },
    ): ComposeDesktopUiModel {
        val folders = listOf(Files.createDirectories(temp.resolve("123")), Files.createDirectories(temp.resolve("456")))
        val images = folders.mapIndexed { index, folder ->
            folder.resolve("sample-$index.png").also {
                ImageIO.write(BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
            }
        }
        val overrides = hashMapOf<String, Function<Array<Any>, Any?>>(
            "message" to Function { args -> message(args[0].toString(), *(args.getOrNull(1) as? Array<*> ?: emptyArray<Any>())) },
            "loadImageClassifierSettings" to Function { DesktopUiToolHost.ImageClassifierSettings(temp.toString(), true, "http://localhost:6999", emptyList()) },
            "recordToolHistory" to Function { null },
            "isImageClassifierDirectory" to Function { true },
            "listImageClassifierFolders" to Function { folders },
            "listImageClassifierImages" to Function { args -> listOf(images[folders.indexOf(args[0])]) },
            "deleteImageClassifierFolderIfEmpty" to Function { null },
            "checkImageClassifierServer" to Function { DesktopUiToolHost.ImageClassifierServer(true, "http://localhost:6999") },
            "resolveImageClassifierArtwork" to Function { args -> Optional.of(DesktopUiToolHost.ImageClassifierArtwork((args[0] as Path).fileName.toString().toLong(), "Example", 0)) },
        )
        return DesktopConfigurationControllerTest.model(stored, overrides, sources)
    }

    private fun awaitClassifierImage(model: ComposeDesktopUiModel, name: String) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
            while (model.busy() || model.snapshot().document().pages().flatMap { descendants(it.content()).toList() }
                    .filterIsInstance<LocalImage>().none { it.path().fileName.toString() == name }) Thread.sleep(10)
        }
    }

    @Test
    @DisplayName("分类忙碌与失败保留缩略图，移动成功只解码下一组图片")
    fun classificationRetainsPreviewUntilFolderChanges() = runComposeUiTest {
        val folders = listOf(Files.createDirectory(temp.resolve("123")), Files.createDirectory(temp.resolve("456")))
        val images = folders.mapIndexed { index, folder ->
            folder.resolve("sample-$index.png").also {
                ImageIO.write(BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
            }
        }
        val target = Files.createDirectory(temp.resolve("classified"))
        val attempts = AtomicInteger()
        val proceed = Semaphore(0)
        val succeeds = AtomicBoolean()
        val overrides = hashMapOf<String, Function<Array<Any>, Any?>>(
            "message" to Function { args -> message(args[0].toString(), *(args.getOrNull(1) as? Array<*> ?: emptyArray<Any>())) },
            "loadImageClassifierSettings" to Function { DesktopUiToolHost.ImageClassifierSettings(temp.toString(), true, "http://localhost:6999", listOf(DesktopUiToolHost.ImageClassifierTarget(target.toString(), "Illustrations"))) },
            "recordToolHistory" to Function { null },
            "isImageClassifierDirectory" to Function { true },
            "listImageClassifierFolders" to Function { folders },
            "listImageClassifierImages" to Function { args -> listOf(images[folders.indexOf(args[0])]) },
            "checkImageClassifierServer" to Function { DesktopUiToolHost.ImageClassifierServer(true, "http://localhost:6999") },
            "resolveImageClassifierArtwork" to Function { args -> Optional.of(DesktopUiToolHost.ImageClassifierArtwork((args[0] as Path).fileName.toString().toLong(), "Example", 0)) },
            "classifyImageFolder" to Function {
                attempts.incrementAndGet()
                check(proceed.tryAcquire(10, TimeUnit.SECONDS))
                if (!succeeds.get()) throw IOException("Classification failed")
                Files.move(images[0], target.resolve(images[0].fileName))
                target
            },
            "deleteImageClassifierFolderIfEmpty" to Function { args -> Files.delete(args[0] as Path); null },
        )
        CountingPngReads().use { reads ->
            DesktopConfigurationControllerTest.model(hashMapOf(), overrides).use { model ->
                var snapshot by mutableStateOf(model.snapshot())
                model.subscribeSnapshots { snapshot = it }.use {
                    setContent {
                        PixivDownloaderTheme("light") {
                            DesktopShell(snapshot.document(), snapshot.revision(), ::resolve) { model.dispatch(snapshot, it) }
                        }
                    }
                    onNodeWithText(message("desktop.ui.page.tools")).performClick()
                    onNodeWithTag("tools.entry.classifier").performClick()
                    waitUntilExactlyOneExists(hasContentDescription("sample-0.png"), timeoutMillis = 10_000)
                    assertEquals(1, reads.count.get())
                    onNodeWithTag("classifier.category.0.select").performClick()
                    try {
                        repeat(2) { attempt ->
                            succeeds.set(attempt == 1)
                            onNodeWithTag("classifier.classify").performClick()
                            waitUntil { attempts.get() == attempt + 1 }
                            onNodeWithTag("classifier.image.0.open").assertIsNotEnabled()
                            waitForIdle()
                            onNodeWithContentDescription("sample-0.png").assertIsDisplayed()
                            assertEquals(1, reads.count.get(), "Busy state must preserve the decoded preview")
                            proceed.release()
                            waitUntil { !model.busy() }
                            if (attempt == 0) {
                                waitUntilExactlyOneExists(hasTestTag("classifier.image.0.open") and isEnabled())
                                onNodeWithTag("classifier.image.0.open").assertIsEnabled()
                                onNodeWithContentDescription("sample-0.png").assertIsDisplayed()
                                assertEquals(1, reads.count.get(), "Failed move must retain the original preview")
                                assertTrue(Files.exists(images[0]))
                            }
                        }
                        waitUntilExactlyOneExists(hasContentDescription("sample-1.png"), timeoutMillis = 10_000)
                        waitForIdle()
                        onNodeWithContentDescription("sample-0.png").assertDoesNotExist()
                        assertEquals(2, reads.count.get(), "Only the next folder needs a new decode")
                        assertFalse(Files.exists(folders[0]))
                        assertTrue(Files.exists(target.resolve(images[0].fileName)))
                    } finally {
                        proceed.release(2)
                    }
                    onNodeWithTag("tools.sheet.close").performClick()
                }
            }
        }
    }

    @Test
    @DisplayName("打开与关闭分类预览保留当前缩略图，只有查看器执行新解码")
    fun classifierPreviewRetainsThumbnail() = runComposeUiTest {
        val image = temp.resolve("sample.png")
        ImageIO.write(BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
        val overrides = hashMapOf<String, Function<Array<Any>, Any?>>(
            "message" to Function { args -> message(args[0].toString(), *(args.getOrNull(1) as? Array<*> ?: emptyArray<Any>())) },
            "loadImageClassifierSettings" to Function { DesktopUiToolHost.ImageClassifierSettings(temp.toString(), true, "http://localhost:6999", emptyList()) },
            "recordToolHistory" to Function { null },
            "isImageClassifierDirectory" to Function { true },
            "listImageClassifierFolders" to Function { listOf(temp.resolve("123")) },
            "listImageClassifierImages" to Function { listOf(image) },
            "checkImageClassifierServer" to Function { DesktopUiToolHost.ImageClassifierServer(true, "http://localhost:6999") },
            "resolveImageClassifierArtwork" to Function { Optional.of(DesktopUiToolHost.ImageClassifierArtwork(123L, "Example", 0)) },
        )
        CountingPngReads().use { reads ->
            DesktopConfigurationControllerTest.model(hashMapOf(), overrides).use { model ->
                var snapshot by mutableStateOf(model.snapshot())
                model.subscribeSnapshots { snapshot = it }.use {
                    setContent {
                        PixivDownloaderTheme("light") {
                            DesktopShell(snapshot.document(), snapshot.revision(), ::resolve) { model.dispatch(snapshot, it) }
                            snapshot.document().dialogs().forEach { dialog ->
                                DocumentDialog(dialog, ::resolve, "Close", { model.dispatch(snapshot, it) }, snapshot.revision())
                            }
                        }
                    }
                    onNodeWithText(message("desktop.ui.page.tools")).performClick()
                    onNodeWithTag("tools.entry.classifier").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithContentDescription("sample.png").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(1, reads.count.get())
                    repeat(2) { index ->
                        onNodeWithTag("classifier.image.0.open").performClick()
                        val viewer = hasContentDescription("sample.png") and !hasTestTag("classifier.image.0.open")
                        waitUntilExactlyOneExists(viewer, timeoutMillis = 10_000)
                        assertEquals(index + 2, reads.count.get(), "Viewer must decode once")
                        onNode(viewer).performKeyInput { pressKey(Key.Escape) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithTag("classifier.image.0.open").fetchSemanticsNodes().isNotEmpty() &&
                                onAllNodesWithContentDescription("sample.png").fetchSemanticsNodes().isNotEmpty()
                        }
                        assertEquals(index + 2, reads.count.get(), "Thumbnail must survive viewer close")
                    }
                    onNodeWithTag("tools.sheet.close").performClick()
                    onNodeWithTag("tools.sheet").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    @DisplayName("真实工具模型直接进入分类与目录检查，紧凑表单、选择和修正动作保持可用")
    fun productionPanels() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val images = (0..7).map { index ->
            val file = temp.resolve("sample-$index.png")
            val image = BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = Color(225 - index * 6, 235 - index * 4, 250 - index * 3)
                fillRect(0, 0, 120, 160)
                color = Color(70, 110, 175)
                fillOval(20, 40, 80, 80)
                dispose()
            }
            ImageIO.write(image, "png", file.toFile())
            file
        }
        val overrides = hashMapOf<String, Function<Array<Any>, Any?>>(
            "message" to Function { args -> message(args[0].toString(), *(args.getOrNull(1) as? Array<*> ?: emptyArray<Any>())) },
            "loadImageClassifierSettings" to Function { DesktopUiToolHost.ImageClassifierSettings("", true, "http://localhost:6999", listOf(DesktopUiToolHost.ImageClassifierTarget(temp.toString(), "Illustrations"))) },
            "recordToolHistory" to Function { null },
            "isImageClassifierDirectory" to Function { true },
            "listImageClassifierFolders" to Function { listOf(temp.resolve("123")) },
            "listImageClassifierImages" to Function { images },
            "checkImageClassifierServer" to Function { DesktopUiToolHost.ImageClassifierServer(true, "http://localhost:6999") },
            "resolveImageClassifierArtwork" to Function { Optional.of(DesktopUiToolHost.ImageClassifierArtwork(123L, "Example", 0)) },
            "checkArtworkFolders" to Function { DesktopUiToolHost.FolderCheckResult(2, listOf(DesktopUiToolHost.FolderArtwork(123L, "Sample artwork", "missing/123", false))) },
            "updateArtworkFolder" to Function { calls += "update:${it[1]}:${it[3]}"; null },
            "openExternalUri" to Function { calls += "guide:${it[0]}"; null },
        )
        DesktopConfigurationControllerTest.model(hashMapOf(), overrides).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme("light") {
                        Box(Modifier.fillMaxSize().background(LocalExperiencePalette.current.surface)) {
                            val root = snapshot.document().pages().first { it.id() == "tools" }.content()
                            ComposeDesktopUiNodeRenderer.Render(root, ::resolve, { event -> synchronized(model) { model.dispatch(model.snapshot(), event) } })
                        }
                    }
                }
                onNodeWithTag("tools.entry.backfill").performClick()
                compactField("tools.backfill.db")
                onNodeWithTag("tools.backfill.db.browse").assertIsDisplayed()
                onNodeWithTag("tools.backfill.proxy").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
                compactField("tools.backfill.proxy-host")
                val host = onNodeWithTag("tools.backfill.proxy-host.field").fetchSemanticsNode().boundsInRoot
                val port = onNodeWithTag("tools.backfill.proxy-port.field").fetchSemanticsNode().boundsInRoot
                assertEquals(host.top, port.top, 1f)
                assertTrue(port.left > host.right)
                screenshot("actual-backfill")
                onNodeWithTag("tools.advanced").performScrollTo().performClick()
                compactField("tools.backfill.delay")
                onNodeWithTag("tools.backfill.run").assertIsDisplayed()
                onNodeWithTag("tools.sheet.close").performClick()
                onNodeWithTag("tools.entry.migration").performClick()
                compactField("tools.migration.db")
                compactField("tools.migration.root")
                screenshot("actual-migration")
                onNodeWithTag("tools.sheet.close").performClick()
                onNodeWithTag("tools.entry.classifier").performClick()
                onNodeWithTag("classifier.default-folder.input").assertIsDisplayed().performTextReplacement(temp.toString())
                onNodeWithTag("classifier.open").performClick()
                waitUntil(timeoutMillis = 5_000) { model.snapshot().document().pages().flatMap { descendants(it.content()).toList() }.any { it.id() == "classifier.image.0.open" } }
                onNodeWithTag("classifier.image.0.open").assertIsDisplayed()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription("sample-0.png").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("sample-0.png").assertIsDisplayed()
                onNodeWithTag("classifier.category.0.select").performClick().assertIsSelected()
                onNodeWithTag("classifier.classify").assertIsEnabled()
                screenshot("actual-classifier")
                onNodeWithTag("tools.sheet.close").performClick()
                onNodeWithTag("tools.entry.folder").performClick()
                onNodeWithTag("tools.folder.db").assertIsDisplayed()
                onNodeWithTag("tools.folder.check").performClick()
                waitUntil(timeoutMillis = 5_000) { !model.busy() }
                onNodeWithTag("artwork.123").performScrollTo().performClick().assertIsSelected()
                onNodeWithTag("tools.folder.new-path").performScrollTo().performTextReplacement(temp.toString())
                screenshot("actual-folder")
                onNodeWithTag("tools.folder.update").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { !model.busy() }
                assertEquals(listOf("update:123:$temp"), calls)
                onNodeWithTag("tools.sheet.close").performClick()
                onNodeWithTag("tools.sheet").assertDoesNotExist()
                onNodeWithTag("tools.entry.media").performScrollTo().performClick()
                onNodeWithTag("tools.ffmpeg.path").assertDoesNotExist()
                onNodeWithTag("tools.media.advanced").performScrollTo().performClick()
                compactField("tools.ffmpeg.path")
                onNodeWithTag("tools.ffmpeg.path.browse").assertIsDisplayed()
                screenshot("actual-media")
                onNodeWithTag("status.ffmpeg.help").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { !model.busy() }
                assertEquals("guide:https://ffmpeg.org/download.html", calls.last())
            }
        }
    }

    @Test
    @DisplayName("高缩放窄窗口中表单可滚动，标题、关闭与底部操作仍可见")
    fun compactWindow() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        DesktopConfigurationControllerTest.model(hashMapOf()).use { model ->
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1.75f)) {
                    PixivDownloaderTheme("dark") {
                        ComposeDesktopUiNodeRenderer.Render(model.snapshot().document().pages().first { it.id() == "tools" }.content(), ::resolve, {})
                    }
                }
            }
            onNodeWithTag("tools.entry.migration").performScrollTo().performClick()
            onNodeWithTag("tools.migration.root").performScrollTo().assertIsDisplayed()
            onNodeWithTag("tools.sheet.close").assertIsDisplayed()
            onNodeWithTag("tools.migration.run").assertIsDisplayed()
            screenshot("actual-migration-compact")
        }
    }

    private fun ComposeUiTest.compactField(id: String) {
        onNodeWithTag("$id.field").performScrollTo()
        val label = onNodeWithTag("$id.label").fetchSemanticsNode().boundsInRoot
        val field = onNodeWithTag("$id.field").fetchSemanticsNode().boundsInRoot
        assertTrue(field.top - label.bottom in 4f..14f, "$id label gap: ${field.top - label.bottom}")
        assertTrue(field.height in 32f..52f, "$id height: ${field.height}")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File("target/tools-ui/$name.png").apply { parentFile.mkdirs() }
        val tag = if (onAllNodesWithTag("tools.media.workspace").fetchSemanticsNodes().isNotEmpty()) "tools.media.workspace" else "tools.sheet"
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", file)
    }

    companion object {
        private val app = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties").reader(Charsets.UTF_8).use(::load)
        }
        private val compose = Properties().apply {
            ToolsWorkspaceLayoutTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        private fun message(key: String, vararg args: Any?) = MessageFormat(app.getProperty(key, key), Locale.US).format(args)
        private fun resolve(token: TextToken): String = if (token.key().isBlank()) token.fallback() else {
            MessageFormat((if (token.key().startsWith("gui.compose.")) compose else app).getProperty(token.key(), token.key()), Locale.US).format(token.arguments().toTypedArray())
        }
    }
}
