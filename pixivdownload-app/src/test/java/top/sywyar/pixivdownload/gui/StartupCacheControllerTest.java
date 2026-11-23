package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("HTTP 启动缓存训练")
class StartupCacheControllerTest {
    @Test
    @DisplayName("能力缺席明确返回不可用且不创建训练任务")
    void reportsUnavailable() throws Exception {
        var controller = new StartupCacheController(Optional::empty, TestI18nBeans.appMessages(), mock(ToolApiRequests.class));
        assertThat(controller.status().available()).isFalse();
        assertThat(controller.start(request("{}"), Locale.ENGLISH).getStatusCode().value()).isEqualTo(503);
        assertThat(controller.cancel(UUID.randomUUID(), Locale.ENGLISH).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("开始与取消只调用共享训练器并返回真实执行身份")
    void delegatesExactExecution() throws Exception {
        var service = mock(DesktopStartupCache.class);
        UUID id = UUID.randomUUID();
        when(service.start()).thenReturn(id);
        when(service.cancel(id)).thenReturn(true);
        var controller = new StartupCacheController(() -> Optional.of(service), TestI18nBeans.appMessages(), mock(ToolApiRequests.class));
        assertThat(controller.start(request("{}"), Locale.ENGLISH).getBody()).isEqualTo(new StartupCacheController.Accepted(id));
        assertThat(controller.cancel(UUID.randomUUID(), Locale.ENGLISH).getStatusCode().value()).isEqualTo(409);
        assertThat(controller.cancel(id, Locale.ENGLISH).getStatusCode().value()).isEqualTo(202);
        verify(service).start(); verify(service).cancel(id);
        assertThatThrownBy(() -> controller.start(request("{\"unexpected\":true}"), Locale.ENGLISH));
        verify(service, times(1)).start();
    }
    private MockHttpServletRequest request(String json) {
        var request = new MockHttpServletRequest(); request.setContent(json.getBytes(StandardCharsets.UTF_8)); return request;
    }
}
