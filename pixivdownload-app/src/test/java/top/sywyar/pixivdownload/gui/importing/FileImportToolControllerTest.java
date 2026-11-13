package top.sywyar.pixivdownload.gui.importing;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileImportToolControllerTest {
    @Test void acknowledgementCannotClearAReplacementCompletion() throws Exception {
        var registry = new DesktopFileImportToolRegistry();
        var source = mock(Source.class);
        when(source.pendingCompletion()).thenReturn(java.util.Optional.of(new Completion("new", 3)));
        registry.register(new ExternalCapabilityOwner("independent-import", "independent-import", 2, 8),
                new Description(DesktopUiText.key("import"), DesktopUiText.key("help")), source);
        var mvc = MockMvcBuilders.standaloneSetup(new FileImportToolController(registry,
                new ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)))).build();
        mvc.perform(post("/api/tools/file-import/completion/acknowledge").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"completionId\":\"old\"}"))
                .andExpect(status().isNotFound());
        verify(source, never()).acknowledgeCompletion(any());
        mvc.perform(post("/api/tools/file-import/completion/acknowledge").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"completionId\":\"new\"}"))
                .andExpect(status().isNoContent());
        verify(source).acknowledgeCompletion("new");
    }
    private static final String ID = "{\"pluginId\":\"independent-import\",\"packageId\":\"independent-import\",\"generation\":2,\"publication\":8}";

    @Test void previewStartAndDesktopReplacementPreserveIdentityAndCredentialBoundary() throws Exception {
        var registry = new DesktopFileImportToolRegistry();
        var owner = new ExternalCapabilityOwner("independent-import", "independent-import", 2, 8);
        var source = mock(Source.class);
        var idle = new Status(Phase.IDLE, 0, 0, 0, 0, 0, List.of());
        var running = new Status(Phase.RUNNING, 1, 0, 0, 0, 0, List.of());
        when(source.status()).thenReturn(idle);
        when(source.inspect(any())).thenReturn(Result.success(new Preview("preview", 1, 1, 1, 0, 0, List.of())));
        when(source.start(anyString(), any())).thenReturn(Result.success(running));
        registry.register(owner, new Description(DesktopUiText.key("import"), DesktopUiText.key("help")), source);
        var mvc = MockMvcBuilders.standaloneSetup(new FileImportToolController(registry,
                new ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)))).build();
        mvc.perform(post("/api/tools/file-import/previews").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"options\":{\"recordFile\":\"D:/records.json\",\"sourceRoot\":\"D:/images\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("preview"));
        verify(source).inspect(new Request("D:/records.json", "D:/images"));
        when(source.status()).thenReturn(running);
        var response = mvc.perform(post("/api/tools/file-import/tasks").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"token\":\"preview\",\"credential\":\"fixture-credential\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.credential").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        verify(source).start("preview", "fixture-credential");
        var id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("executionId").asText();
        registry.source(new Identity("independent-import", "independent-import", 2, 8)).start("desktop", null);
        mvc.perform(post("/api/tools/file-import/tasks/cancel").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"executionId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest());
        verify(source, never()).cancel();
        registry.unregister(owner);
        mvc.perform(post("/api/tools/file-import/tasks").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"token\":\"late\"}"))
                .andExpect(status().isConflict());
        verify(source, never()).start(eq("late"), any());
    }

    @Test void malformedCredentialBodyDoesNotReachProvider() throws Exception {
        var registry = mock(DesktopFileImportToolRegistry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FileImportToolController(registry,
                new ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)))).build();
        for (String body : List.of("null", "{} {}", "{\"credential\":42}", "{\"token\":\"a\",\"token\":\"b\"}")) {
            mvc.perform(post("/api/tools/file-import/tasks").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/tools/file-import/tasks").contentType("application/json").content(" ".repeat(65537)))
                .andExpect(status().isContentTooLarge());
        verifyNoInteractions(registry);
    }
}
