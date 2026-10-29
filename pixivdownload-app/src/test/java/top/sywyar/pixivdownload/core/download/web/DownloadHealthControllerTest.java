package top.sywyar.pixivdownload.core.download.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;
import java.util.Locale;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("宿主下载健康检查")
class DownloadHealthControllerTest {
    private final org.springframework.test.web.servlet.MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new DownloadHealthController(HostTestMessages.messages())).build();
    @Test
    @DisplayName("GET /api/download/status 应返回服务状态")
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/api/download/status").locale(Locale.US))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Service is running normally"));
    }
}
