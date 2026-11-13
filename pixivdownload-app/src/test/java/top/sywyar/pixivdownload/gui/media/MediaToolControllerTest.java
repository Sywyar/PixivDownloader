package top.sywyar.pixivdownload.gui.media;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.DesktopToolHistory;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaToolControllerTest {
    private static final String ID = "{\"pluginId\":\"independent-tool\",\"packageId\":\"independent-tool\",\"generation\":3,\"publication\":5}";

    @Test
    @org.junit.jupiter.api.DisplayName("程序缺少目标插件执行权限时拒绝且不访问执行器")
    void deniesOtherProviderBeforeLookup() throws Exception {
        var registry = mock(DesktopMediaToolRegistry.class);
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new MediaToolController(registry, mock(DesktopToolHistory.class),
                new top.sywyar.pixivdownload.gui.ToolApiRequests(messages, mock(WebI18nService.class))))
                .setControllerAdvice(new top.sywyar.pixivdownload.GlobalExceptionHandler(messages)).build();
        var identity = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(java.util.UUID.randomUUID(),
                java.util.Set.of("core:media.execute", "another-tool:media.execute"));
        mvc.perform(post("/api/tools/media/tasks").contentType("application/json")
                .requestAttr(top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, identity)
                .content("{\"identity\":" + ID + ",\"token\":\"preview\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registry);
    }

    @Test void unknownProviderUsesSameHttpAndDesktopExecutionAndOldOwnerIsRejected() throws Exception {
        var registry = new DesktopMediaToolRegistry();
        var owner = new ExternalCapabilityOwner("independent-tool", "independent-tool", 3, 5);
        var identity = new Identity(owner.pluginId(), owner.packageId(), 3, 5);
        var source = mock(Source.class);
        var running = new Status("running", 1, 0, 0, List.of());
        when(source.status()).thenReturn(running);
        when(source.start(anyString())).thenReturn(new Result<>(running, null));
        when(source.capabilities()).thenReturn(new Result<>(new Report("C:/private/ffmpeg.exe", "SYSTEM", List.of()), null));
        registry.register(owner, new Description(DesktopUiText.key("tool"), "independent", "png", "gif"), source);
        var mvc = MockMvcBuilders.standaloneSetup(new MediaToolController(registry, mock(DesktopToolHistory.class),
                new top.sywyar.pixivdownload.gui.ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)))).build();
        mvc.perform(get("/api/tools/media")).andExpect(status().isOk()).andExpect(jsonPath("$[0].identity.pluginId").value("independent-tool"));
        var response = mvc.perform(post("/api/tools/media/tasks").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"token\":\"first\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var old = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("executionId").asText();
        registry.source(identity).start("desktop-replacement");
        mvc.perform(post("/api/tools/media/tasks/cancel").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"executionId\":\"" + old + "\"}"))
                .andExpect(status().isBadRequest());
        verify(source, never()).cancel();
        mvc.perform(get("/api/tools/media/tasks/current").param("pluginId", owner.pluginId())
                .param("packageId", owner.packageId()).param("generation", "3").param("publication", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status.state").value("running"));
        mvc.perform(post("/api/tools/media/capabilities").contentType("application/json").content(ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.command").doesNotExist());
        registry.unregister(owner);
        mvc.perform(post("/api/tools/media/tasks").contentType("application/json")
                .content("{\"identity\":" + ID + ",\"token\":\"late\"}"))
                .andExpect(status().isConflict());
        verify(source, never()).start("late");
    }

    @Test void invalidAndOversizedBodiesCannotInvokeProvider() throws Exception {
        var registry = mock(DesktopMediaToolRegistry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MediaToolController(registry, mock(DesktopToolHistory.class),
                new top.sywyar.pixivdownload.gui.ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)))).build();
        for (String body : List.of("null", "{} {}", "{\"identity\":null}", "{\"token\":1}", "{\"unexpected\":true}")) {
            mvc.perform(post("/api/tools/media/tasks").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/tools/media/tasks").contentType("application/json").content(" ".repeat(4097)))
                .andExpect(status().isContentTooLarge());
        verifyNoInteractions(registry);
    }
}
