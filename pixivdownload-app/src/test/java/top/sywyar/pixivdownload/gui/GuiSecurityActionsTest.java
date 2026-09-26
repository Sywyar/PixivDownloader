package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.common.ServerStateProvider;
import top.sywyar.pixivdownload.gui.controller.GuiStatusController;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GUI 管理员会话注销边界")
class GuiSecurityActionsTest {
    @Test
    @DisplayName("拒绝非本地来源且只有事务成功才返回成功")
    void rejectsRemoteAndReportsPersistenceFailure() throws Exception {
        var state = mock(ServerStateProvider.class);
        var controller = new GuiStatusController(state, null, null, null, TestI18nBeans.appMessages(), null, null);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        assertThat(controller.logoutAll(request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(state);
        request.setRemoteAddr("127.0.0.1");
        assertThat(controller.logoutAll(request).getBody().success()).isTrue();
        doThrow(new IOException("disk")).when(state).revokeAllSessions();
        var failed = controller.logoutAll(request);
        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(failed.getBody().code()).isEqualTo("save-failed");
        assertThat(failed.getBody().error()).isNotBlank().isNotEqualTo("save-failed");
        assertThat(failed.getBody().success()).isFalse();
        doThrow(new IllegalStateException()).when(state).revokeAllSessions();
        assertThat(controller.logoutAll(request).getStatusCode().value()).isEqualTo(409);
    }
}
