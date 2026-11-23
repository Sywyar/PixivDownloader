package top.sywyar.pixivdownload.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("环境迁移 HTTP 的确认、密码清理与错误边界")
class EnvironmentTransferControllerTest {
    @TempDir Path root;
    final DesktopEnvironmentTransfer transfer = mock(DesktopEnvironmentTransfer.class);
    final JsonMapper json = JsonMapper.builder().build();

    private org.springframework.test.web.servlet.MockMvc mvc() {
        var messages = TestI18nBeans.appMessages();
        return MockMvcBuilders.standaloneSetup(new EnvironmentTransferController(transfer,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages, mock(EnvironmentExportService.class))).build();
    }

    @Test
    @DisplayName("确认透传精确预览与路径选择，返回待恢复而非成功，并在调用后擦除密码")
    void confirmsSelectionAndClearsPassword() throws Exception {
        var password = new AtomicReference<char[]>();
        var field = new FieldKey("host", "download.root-folder");
        String destination = root.resolve("works").toString();
        doAnswer(call -> {
            password.set(call.getArgument(3));
            assertThat(password.get()).containsExactly('s', 'e', 'c', 'r', 'e', 't');
            assertThat((Map<FieldKey, String>) call.getArgument(2)).containsEntry(field, destination);
            return null;
        }).when(transfer).importConfiguration(eq("preview-id"), eq(Set.of(field)), anyMap(), any(), eq(false), eq(Set.of()), eq(Map.of()));
        var request = new EnvironmentTransferController.Restore("preview-id", Set.of(field),
                List.of(new EnvironmentTransferController.PathMapping(field, destination)), "secret".toCharArray(), false, Set.of(), Map.of());
        mvc().perform(post("/api/settings/environment/restore").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("RESTORE_PENDING"));
        assertThat(password.get()).containsOnly('\0');
        when(transfer.restoreState()).thenReturn(new RestoreState(Status.FAILED, "transfer.database-failed"));
        mvc().perform(get("/api/settings/environment/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("预览失败也擦除密码，私有异常细节不回显，旧预览保持冲突")
    void hidesDetailsAndPreservesConflict() throws Exception {
        var password = new AtomicReference<char[]>();
        when(transfer.previewConfiguration(any(), any())).thenAnswer(call -> {
            password.set(call.getArgument(1));
            throw new IOException("private-password-and-path");
        });
        var request = new EnvironmentTransferController.Inspect(root.resolve("archive.pdt").toString(), "secret".toCharArray());
        mvc().perform(post("/api/settings/environment/preview").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("transfer.failed"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-password"))));
        assertThat(password.get()).containsOnly('\0');
        doThrow(new IOException("transfer.target-changed")).when(transfer).previewConfiguration(any(), any());
        mvc().perform(post("/api/settings/environment/preview").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("transfer.target-changed"));
    }

    @Test
    @DisplayName("相对归档路径与重复映射在迁移服务前拒绝")
    void rejectsInvalidPathsBeforeService() throws Exception {
        mvc().perform(post("/api/settings/environment/preview").contentType("application/json")
                        .content("{\"archive\":\"relative.pdt\"}"))
                .andExpect(status().isBadRequest());
        var field = new FieldKey("host", "download.root-folder");
        var mapping = new EnvironmentTransferController.PathMapping(field, root.toString());
        var request = new EnvironmentTransferController.Restore("id", Set.of(field), List.of(mapping, mapping), null, false, Set.of(), Map.of());
        mvc().perform(post("/api/settings/environment/restore").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transfer);
    }
}
