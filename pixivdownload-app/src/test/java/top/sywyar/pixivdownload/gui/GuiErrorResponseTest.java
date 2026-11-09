package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.config.RuntimeConfigReloadService;
import top.sywyar.pixivdownload.gui.controller.GuiStatusController;
import top.sywyar.pixivdownload.gui.controller.UpdateController;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.update.UpdateService;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("GUI 接口的用户错误与内部诊断分离")
class GuiErrorResponseTest {
    private static final String DETAIL = "internal-probe C:/private/config.yaml token=private-value";

    @Test
    @DisplayName("热重载的 IO 与运行时失败均返回安全 JSON，拒绝远程调用")
    void reloadErrorsAreSafe() throws Exception {
        var reload = mock(RuntimeConfigReloadService.class);
        var controller = new GuiStatusController(null, null, reload, TestI18nBeans.appMessages(), null, null, null);
        var remote = new MockHttpServletRequest();
        remote.setRemoteAddr("203.0.113.7");
        assertThat(controller.reloadConfig(null, remote).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(reload);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(reload.reloadHotConfig(anyList())).thenThrow(new IOException(DETAIL), new IllegalStateException(DETAIL));
        for (int i = 0; i < 2; i++) {
            String body = mvc.perform(post("/api/gui/config/reload").locale(Locale.US))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("gui.config.reload.failed"))
                    .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(Locale.US, "gui.config.reload.failed")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(DETAIL, "internal-probe", "private-value");
        }
    }

    @Test
    @DisplayName("更新下载与安装失败不返回内部异常且保留状态")
    void updateErrorsAreSafe() throws Exception {
        var service = mock(UpdateService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new UpdateController(service, TestI18nBeans.appMessages())).build();
        doThrow(new IllegalStateException(DETAIL)).when(service).startDownloadAsync(false);
        doThrow(new IOException(DETAIL)).when(service).launchInstallerAndExit();
        for (String action : new String[]{"download", "install"}) {
            String code = action.equals("download") ? "update.download.start-failed" : "update.install.failed";
            String body = mvc.perform(post("/api/gui/update/" + action).locale(Locale.US))
                    .andExpect(status().is(action.equals("download") ? 409 : 500))
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(Locale.US, code)))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("internal-probe", "private-value");
        }
    }

    @Test
    @DisplayName("更新下载进度保留失败与字节数，错误使用请求语言的安全提示")
    void updateProgressErrorIsSafe() throws Exception {
        var service = mock(UpdateService.class);
        when(service.getDownloadProgress()).thenReturn(new UpdateService.DownloadProgress(12, 30, false, true, DETAIL, null));
        var mvc = MockMvcBuilders.standaloneSetup(new UpdateController(service, TestI18nBeans.appMessages())).build();
        String body = mvc.perform(get("/api/gui/update/download/progress").locale(Locale.US))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(12))
                .andExpect(jsonPath("$.total").value(30))
                .andExpect(jsonPath("$.failed").value(true))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages().get(Locale.US, "update.download.failed")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("internal-probe", "private-value");
    }
}
