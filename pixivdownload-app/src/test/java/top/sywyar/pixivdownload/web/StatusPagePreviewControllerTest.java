package top.sywyar.pixivdownload.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DisplayName("开发模式状态页触发器")
class StatusPagePreviewControllerTest {

    private final MockMvc mockMvc = standaloneSetup(new StatusPagePreviewController()).build();

    @Test
    @DisplayName("仅接受 4xx / 5xx 并交给容器错误分派")
    void dispatchesOnlyErrorStatuses() throws Exception {
        mockMvc.perform(get("/__dev/error/418")).andExpect(status().is(418));
        mockMvc.perform(get("/__dev/error/501")).andExpect(status().is(501));
        mockMvc.perform(get("/__dev/error/399")).andExpect(status().isNotFound());
        mockMvc.perform(get("/__dev/error/600")).andExpect(status().isNotFound());
    }
}
