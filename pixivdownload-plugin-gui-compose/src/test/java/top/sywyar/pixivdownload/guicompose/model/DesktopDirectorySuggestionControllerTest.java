package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 候选目录确认")
class DesktopDirectorySuggestionControllerTest {
    static DesktopUiHost.GuiValue snapshot() {
        return DesktopUiHost.GuiValue.of(Map.of("directories", List.of(Map.of(
                "owner", Map.of("pluginId", "sample", "packageId", "sample", "generation", 2L, "publication", 3L),
                "suggestion", Map.of("suggestionId", "first", "configurationKey", "sample.source-root", "directory", "C:/downloads"),
                "displayName", Map.of("namespace", "", "key", "", "fallback", "Sample", "arguments", List.of())))));
    }

    @Test @DisplayName("改选只影响弹窗草稿，确认提交精确 owner 与路径")
    void confirmsEditedDirectory() throws Exception {
        var requests = new LinkedBlockingQueue<Object>();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of("guiPostJson", args -> {
            assertEquals("control-center/directory", args[0]);
            requests.add(args[1]);
            return new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of("code", "SAVED")), "", false);
        }))) {
            model.directorySuggestions.refresh(snapshot());
            assertTrue(model.isDialogOpen("directory-suggestion"));
            assertTrue(requests.isEmpty());
            synchronized (model) {
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                        "directory-suggestion.path", DesktopUiNode.Value.text("D:/chosen")));
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                        "directory-suggestion.confirm", DesktopUiNode.Value.empty()));
            }
            Map<?, ?> request = (Map<?, ?>) requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("D:/chosen", request.get("directory"));
            assertEquals(false, request.get("dismiss"));
            assertEquals(Map.of("pluginId", "sample", "packageId", "sample", "generation", 2L, "publication", 3L), request.get("owner"));
        }
    }

    @Test @DisplayName("取消不保存且重复观察不重新弹窗，贡献撤回后关闭弹窗")
    void cancelsAndWithdrawsWithoutSaving() throws Exception {
        var requests = new LinkedBlockingQueue<Object>();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of("guiPostJson", args -> {
            requests.add(args[1]);
            return new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of()), "", false);
        }))) {
            model.directorySuggestions.refresh(snapshot());
            synchronized (model) {
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                        "directory-suggestion.cancel", DesktopUiNode.Value.empty()));
            }
            Map<?, ?> request = (Map<?, ?>) requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(true, request.get("dismiss"));
            model.directorySuggestions.refresh(snapshot());
            assertFalse(model.isDialogOpen("directory-suggestion"));
        }
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>())) {
            model.directorySuggestions.refresh(snapshot());
            assertTrue(model.isDialogOpen("directory-suggestion"));
            model.directorySuggestions.refresh(DesktopUiHost.GuiValue.of(Map.of("directories", List.of())));
            assertFalse(model.isDialogOpen("directory-suggestion"));
        }
    }
}
