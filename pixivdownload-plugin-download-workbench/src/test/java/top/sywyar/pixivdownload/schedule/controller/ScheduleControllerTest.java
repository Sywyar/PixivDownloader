package top.sywyar.pixivdownload.schedule.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.schedule.ScheduleService;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
}
