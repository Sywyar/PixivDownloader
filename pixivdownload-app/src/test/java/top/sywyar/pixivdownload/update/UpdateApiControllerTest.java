package top.sywyar.pixivdownload.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("管理员软件更新 API")
class UpdateApiControllerTest {
    @Test
    @DisplayName("进度不返回安装路径或异常原文，参数拒绝发生在执行前")
    void projectsSafeProgressAndValidatesCommands() throws Exception {
        var service = mock(UpdateService.class);
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new UpdateApiController(service, messages,
                new ToolApiRequests(messages, mock(WebI18nService.class)))).build();
        mvc.perform(post("/api/admin/update/download").param("channel", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/update/install").contentType("application/json")
                .content("{\"sha256\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.getDownloadProgress()).thenReturn(new UpdateService.DownloadProgress(2, 10, false, true,
                "secret-error", "private-installer-path"));
        mvc.perform(get("/api/admin/update/download/progress"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("UPDATE_DOWNLOAD_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-error"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-installer-path"))));
        mvc.perform(post("/api/admin/update/download").param("channel", "nightly"))
                .andExpect(status().isAccepted());
        verify(service).startDownloadAsync(true);
    }
}
