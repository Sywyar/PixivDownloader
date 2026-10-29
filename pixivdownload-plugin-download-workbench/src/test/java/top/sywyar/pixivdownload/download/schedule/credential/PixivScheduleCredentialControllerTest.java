package top.sywyar.pixivdownload.download.schedule.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduledTaskCredentials;
import top.sywyar.pixivdownload.download.schedule.credential.PixivScheduleCredentialController;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 计划凭证兼容接口")
class PixivScheduleCredentialControllerTest {
    @Mock private ScheduledTaskCredentials scheduleService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    @BeforeEach void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PixivScheduleCredentialController(scheduleService, WorkbenchTestMessages.messages())).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("绑定来源凭证时原样传递 publication 激活令牌")
    void authorizeCookiePassesActivationToken() throws Exception {
        mockMvc.perform(post("/api/schedule/tasks/{id}/authorize-cookie", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "cookie", "PHPSESSID=42_secret",
                                "activationToken", "activation-42"))))
                .andExpect(status().isOk());

        verify(scheduleService).bindCredential(
                42L, "PHPSESSID=42_secret", "activation-42");
    }

    @Test
    @DisplayName("绑定来源凭证可只通过中性请求头传入而不进入 JSON 请求体")
    void authorizeCookieAcceptsAcquisitionCredentialHeader() throws Exception {
        mockMvc.perform(post("/api/schedule/tasks/{id}/authorize-cookie", 42L)
                        .header("X-Acquisition-Credential", "header-credential-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "activationToken", "activation-42"))))
                .andExpect(status().isOk());

        verify(scheduleService).bindCredential(
                42L, "header-credential-42", "activation-42");
    }

    @Test
    @DisplayName("Pixiv 撤销 Cookie 兼容端点保留旧 POST 路由并委托通用凭证服务")
    void pixivRevokeCookieCompatibilityRouteRevokesCurrentBinding() throws Exception {
        mockMvc.perform(post("/api/schedule/tasks/{id}/revoke-cookie", 45L))
                .andExpect(status().isOk());

        verify(scheduleService).revokeCredential(45L);
    }

    @Test
    @DisplayName("Pixiv 账号恢复兼容端点委托宿主解析当前策略 publication")
    void pixivAccountResumeUsesCurrentPolicyPublicationAdapter() throws Exception {
        mockMvc.perform(post("/api/schedule/account/{accountId}/resume", "account-45")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "mode", "defer",
                                "minutes", 90))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(scheduleService).applyCurrentCredentialPolicyAction(
                "download-workbench", "pixiv-cookie", "account-45", "defer",
                Map.of("minutes", "90"));
    }
}
