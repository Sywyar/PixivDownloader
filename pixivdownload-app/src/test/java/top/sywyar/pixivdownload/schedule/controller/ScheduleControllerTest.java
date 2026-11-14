package top.sywyar.pixivdownload.schedule.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;

import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    @DisplayName("程序不能修改其它来源的任务，列表只返回授权来源")
    void isolatesProgramSourceTargets() throws Exception {
        var allowed = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView.class);
        var denied = org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleTaskView.class);
        when(allowed.sourceOwnerPluginId()).thenReturn("source-a");
        when(denied.sourceOwnerPluginId()).thenReturn("source-b");
        when(scheduleService.list()).thenReturn(List.of(allowed, denied));
        when(scheduleService.get(42L)).thenReturn(denied);
        var identity = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(java.util.UUID.randomUUID(),
                java.util.Set.of("core:schedule.read", "core:schedule.pause", "source-a:schedule.manage"));
        mockMvc.perform(get("/api/schedule/tasks").requestAttr(
                        top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, identity))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceOwnerPluginId").value("source-a"));
        mockMvc.perform(post("/api/schedule/tasks/42/pause").requestAttr(
                        top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, identity))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(scheduleService, org.mockito.Mockito.never()).pause(42L);
        var permitted = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(identity.id(),
                java.util.Set.of("core:schedule.pause", "source-b:schedule.manage"));
        mockMvc.perform(post("/api/schedule/tasks/42/pause").requestAttr(
                        top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, permitted))
                .andExpect(status().isOk());
        verify(scheduleService).pause(42L);
    }

    @Test
    @DisplayName("程序创建计划前核对当前来源和激活身份，拒绝时不调用保存")
    void rejectsUnapprovedSourceBeforeCreate() throws Exception {
        var source = org.mockito.Mockito.mock(ScheduleSourceManifestView.Source.class);
        when(source.sourceType()).thenReturn("example");
        when(source.activationToken()).thenReturn("active");
        when(source.ownerPluginId()).thenReturn("source-b");
        when(scheduleService.sources()).thenReturn(new ScheduleSourceManifestView("epoch", 1, List.of(source)));
        var identity = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(java.util.UUID.randomUUID(),
                java.util.Set.of("core:schedule.create", "source-a:schedule.manage"));
        mockMvc.perform(post("/api/schedule/tasks").requestAttr(
                        top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE, identity)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker\",\"sourceType\":\"example\",\"activationToken\":\"active\","
                                + "\"definitionJson\":\"{}\",\"triggerKind\":\"interval\",\"intervalMinutes\":30}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(scheduleService, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any());
    }

    private static final String WORK_TYPE = "novel/自定义?'\"#_%";
    private static final String WORK_ID = "001/路径?mode=\"人工\"&x='y'#_%";

    @Mock
    private ScheduleService scheduleService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ScheduleController(scheduleService, HostTestMessages.messages()))
                .setControllerAdvice(new GlobalExceptionHandler(TestI18nBeans.appMessages())).build();
        objectMapper = JsonMapper.builderWithJackson2Defaults().build();
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
    @DisplayName("中性凭证端点仅通过取得凭证头绑定当前来源 publication")
    void genericCredentialEndpointBindsCurrentSourcePublication() throws Exception {
        mockMvc.perform(post("/api/schedule/tasks/{id}/credential", 43L)
                        .header("X-Acquisition-Credential", "generic-credential-43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "activationToken", "activation-43"))))
                .andExpect(status().isOk());

        verify(scheduleService).bindCredential(
                43L, "generic-credential-43", "activation-43");
    }

    @Test
    @DisplayName("缺少取得凭证头返回本地化 JSON 400 且不调用绑定服务")
    void missingCredentialHeaderDoesNotReachService() throws Exception {
        mockMvc.perform(post("/api/schedule/tasks/{id}/credential", 43L)
                        .locale(Locale.US).accept(MediaType.TEXT_HTML)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activationToken\":\"activation-43\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("error.request.param.invalid"))
                .andExpect(jsonPath("$.error").value(TestI18nBeans.appMessages()
                        .get(Locale.US, "error.request.param.invalid")));
        verifyNoInteractions(scheduleService);
    }

    @Test
    @DisplayName("中性凭证端点使用 DELETE 解除当前策略绑定")
    void genericCredentialEndpointRevokesCurrentBinding() throws Exception {
        mockMvc.perform(delete("/api/schedule/tasks/{id}/credential", 44L))
                .andExpect(status().isOk());

        verify(scheduleService).revokeCredential(44L);
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
