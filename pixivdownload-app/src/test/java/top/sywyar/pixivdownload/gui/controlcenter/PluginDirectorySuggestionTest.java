package top.sywyar.pixivdownload.gui.controlcenter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("插件目录候选的确认、路径校验和生命周期")
class PluginDirectorySuggestionTest {
    @TempDir Path temporary;
    private static final String KEY = "sample.source-root";

    private PluginDirectorySuggestion source(DesktopDirectorySuggestionSource provider) {
        return new PluginDirectorySuggestion("sample", provider, temporary.resolve("config.properties"),
                List.of(new GuiConfigFieldContribution(KEY, "sample", "label", "", GuiConfigFieldType.PATH_DIR,
                        "", 0, false, GuiConfigEffect.HOT_RELOAD)));
    }
    private DesktopDirectorySuggestion value(String path) { return new DesktopDirectorySuggestion("candidate", KEY, path); }

    @Test @DisplayName("展示和取消不写配置，允许改选且不覆盖已有目录")
    void explicitConfirmationIsTheOnlyWrite() throws Exception {
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        Path selected = Files.createDirectory(temporary.resolve("selected"));
        var source = source(() -> Optional.of(value(candidate.toString())));
        assertTrue(source.suggestion().isPresent());
        assertFalse(Files.exists(temporary.resolve("config.properties")));
        source.dismiss("candidate");
        assertTrue(source.suggestion().isEmpty());
        assertFalse(Files.exists(temporary.resolve("config.properties")));
        assertThrows(IllegalStateException.class, () -> source.confirm("candidate", selected.toString()));
        var next = source(() -> Optional.of(value(candidate.toString())));
        next.confirm("candidate", selected.toString());
        var editor = new PropertiesConfigFileEditor(temporary.resolve("config.properties"));
        assertEquals(selected.toString(), editor.readAll(List.of(KEY)).get(KEY));
        assertTrue(next.suggestion().isEmpty());
        assertThrows(IllegalStateException.class, () -> next.confirm("candidate", candidate.toString()));
        assertEquals(selected.toString(), editor.readAll(List.of(KEY)).get(KEY));
    }

    @Test @DisplayName("拒绝相对路径、普通文件、未声明字段，确认时重新检查目录")
    void checksPathsAndDeclaredOwnerField() throws Exception {
        assertThrows(java.io.UncheckedIOException.class, () -> source(() -> Optional.of(value("relative"))).suggestion());
        Path file = Files.writeString(temporary.resolve("file.txt"), "text");
        assertThrows(java.io.UncheckedIOException.class, () -> source(() -> Optional.of(value(file.toString()))).suggestion());
        var foreign = source(() -> Optional.of(new DesktopDirectorySuggestion("candidate", "other.source-root", temporary.toString())));
        assertTrue(foreign.suggestion().isEmpty());
        Path candidate = Files.createDirectory(temporary.resolve("candidate"));
        var source = source(() -> Optional.of(value(candidate.toString())));
        assertTrue(source.suggestion().isPresent());
        Files.delete(candidate);
        Files.writeString(candidate, "not a directory");
        assertThrows(java.io.UncheckedIOException.class, () -> source.confirm("candidate", temporary.toString()));
        assertFalse(Files.exists(temporary.resolve("config.properties")));
    }

    @Test @DisplayName("用户在设置先保存的目录不能被旧候选覆盖")
    void ordinaryConfigSaveWinsOverOldPrompt() throws Exception {
        var source = source(() -> Optional.of(value(temporary.toString())));
        assertTrue(source.suggestion().isPresent());
        var editor = new PropertiesConfigFileEditor(temporary.resolve("config.properties"));
        editor.writeAll(Map.of(KEY, "already chosen"));
        assertThrows(IllegalStateException.class, () -> source.confirm("candidate", temporary.toString()));
        assertEquals("already chosen", editor.readAll(List.of(KEY)).get(KEY));
    }

    @Test @DisplayName("确认写入受 publication 租约保护，撤回后旧请求不再写入")
    void confirmationParticipatesInDrain() throws Exception {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var preparation = invocations.allocatePreparation("sample", "sample", 1);
        invocations.installPreparation(preparation);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var proxy = invocations.prepareProxy(preparation, DirectorySuggestionCapability.class, source(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return Optional.of(value(temporary.toString()));
        }));
        var publication = invocations.publish(preparation);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var saved = worker.submit(() -> { proxy.confirm("candidate", temporary.toString()); return true; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var drain = invocations.withdraw(publication).orElseThrow();
            assertFalse(drain.isDrained());
            assertThrows(RuntimeException.class, () -> proxy.confirm("candidate", temporary.toString()));
            release.countDown();
            assertTrue(saved.get(5, TimeUnit.SECONDS));
            assertTrue(drain.isDrained());
            assertEquals(temporary.toString(), new PropertiesConfigFileEditor(temporary.resolve("config.properties"))
                    .readAll(List.of(KEY)).get(KEY));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
