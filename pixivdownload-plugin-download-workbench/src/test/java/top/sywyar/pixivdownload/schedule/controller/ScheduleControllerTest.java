package top.sywyar.pixivdownload.schedule.controller;

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
import top.sywyar.pixivdownload.schedule.ScheduleService;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;

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
@DisplayName("计划任务管理接口")
class ScheduleControllerTest {

    private static final String WORK_TYPE = "novel/自定义?'\"#_%";
    private static final String WORK_ID = "001/路径?mode=\"人工\"&x='y'#_%";

    @Mock
    private ScheduleService scheduleService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduleController(scheduleService)).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("清除 pending 使用 JSON 原样传递含路径与引号的复合身份")
    void clearPendingPreservesOpaqueCompositeIdentity() throws Exception {
        mockMvc.perform(delete("/api/schedule/tasks/{id}/pending", 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "workType", WORK_TYPE,
                                "workId", WORK_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(scheduleService).clearPending(42L, WORK_TYPE, WORK_ID);
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

        verify(scheduleService).authorizeCookie(
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

        verify(scheduleService).authorizeCookie(
                42L, "header-credential-42", "activation-42");
    }

    @Test
    @DisplayName("来源清单返回 owner 盖章字段并禁止缓存")
    void sourceManifestIsNoStoreAndKeepsStampedFields() throws Exception {
        when(scheduleService.sources()).thenReturn(new ScheduleSourceManifestView(
                "epoch-1",
                7L,
                List.of(new ScheduleSourceManifestView.Source(
                        "fixture:source",
                        List.of("FIXTURE_SOURCE"),
                        "fixture-owner",
                        "fixture-package",
                        3L,
                        11L,
                        "activation-1",
                        "fixture.definition",
                        2,
                        new ScheduleSourceManifestView.Presentation(
                                "fixture", "fixture.name", "fixture.description",
                                "schedule", "neutral"),
                        List.of("fixture"),
                        List.of("fixture:work"),
                        new ScheduleSourceManifestView.Frontend(
                                1, "/fixture/schedule-source.js")))));

        mockMvc.perform(get("/api/schedule/sources"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.epoch").value("epoch-1"))
                .andExpect(jsonPath("$.revision").value(7))
                .andExpect(jsonPath("$.sources[0].legacyAliases[0]").value("FIXTURE_SOURCE"))
                .andExpect(jsonPath("$.sources[0].ownerPluginId").value("fixture-owner"))
                .andExpect(jsonPath("$.sources[0].activationToken").value("activation-1"))
                .andExpect(jsonPath("$.sources[0].frontend.moduleUrl")
                        .value("/fixture/schedule-source.js"));
    }
}
