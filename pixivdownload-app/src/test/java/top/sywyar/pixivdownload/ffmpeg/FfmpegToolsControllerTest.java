package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class FfmpegToolsControllerTest {
    @Test void temporaryProxyIsValidatedAndSafeFailuresAreLocalized() throws Exception {
        var service = mock(FfmpegInstallService.class);
        var proxy = new ProxyConfig();
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new FfmpegToolsController(service, proxy, messages,
                new ToolApiRequests(messages, mock(WebI18nService.class)))).build();
        try (var installer = mockStatic(FfmpegInstaller.class); var locator = mockStatic(FfmpegLocator.class)) {
            installer.when(FfmpegInstaller::supportsManagedDownload).thenReturn(true);
            locator.when(FfmpegLocator::locate).thenReturn(java.util.Optional.empty());
            mvc.perform(post("/api/tools/ffmpeg/tasks").contentType("application/json")
                    .content("{\"proxy\":{\"enabled\":true,\"host\":\"\",\"port\":7890}}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(service);
            var accepted = new FfmpegInstallService.Snapshot(UUID.randomUUID(), "RUNNING", FfmpegInstaller.ProgressStage.CONNECTING, 0, -1, "", null);
            when(service.start(any())).thenReturn(accepted);
            mvc.perform(post("/api/tools/ffmpeg/tasks").contentType("application/json").content("{}"))
                    .andExpect(status().isAccepted());
            verify(service).start(new FfmpegInstaller.ProxySettings(proxy.isEnabled(), proxy.getHost(), proxy.getPort()));
            var error = new FfmpegInstaller.InstallationException("FFMPEG_INSTALL_UNSAFE_DIRECTORY", "private-path",
                    "ffmpeg.api.unsafe-directory");
            when(service.snapshot()).thenReturn(new FfmpegInstallService.Snapshot(accepted.executionId(), "FAILED",
                    accepted.stage(), 0, -1, error.code(), error.text()));
            mvc.perform(get("/api/tools/ffmpeg").locale(java.util.Locale.US)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.task.failureCode").value("FFMPEG_INSTALL_UNSAFE_DIRECTORY"))
                    .andExpect(jsonPath("$.task.error").value(not(containsString("ffmpeg.api."))))
                    .andExpect(content().string(not(containsString("private-path"))));
        }
    }
}
