package top.sywyar.pixivdownload.core.download.web;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("管理员公开下载任务 HTTP 契约")
class DownloadTasksControllerTest {
    @Test void historyRequiresAdminAndPreservesCursorAndStructuredFailure() throws Exception {
        var tasks = mock(DownloadTasks.class);
        var identities = mock(RequestOwnerIdentityResolver.class);
        var owner = RequestOwnerIdentity.adminScope();
        when(identities.resolve(any())).thenReturn(owner);
        var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "opaque-key");
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var failed = new DownloadTaskSnapshot(attempt, "example", "fixture", DownloadEvent.Phase.FAILED,
                time, 0, DownloadEvent.FailureReason.CREDENTIALS_UNAVAILABLE);
        var cursor = new DownloadTasks.HistoryCursor(time, attempt.attemptId());
        when(tasks.history(null, 1, owner)).thenReturn(new DownloadTasks.HistoryPage(List.of(failed), cursor));
        when(tasks.history(cursor, 1, owner)).thenReturn(new DownloadTasks.HistoryPage(List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(tasks, identities, HostTestMessages.messages())).build();
        mvc.perform(get("/api/download/tasks/history").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks[0].failureReason").value("CREDENTIALS_UNAVAILABLE"))
                .andExpect(jsonPath("$.beforeTime").value(time.toString())).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/download/tasks/history").param("limit", "1")
                .param("beforeTime", time.toString()).param("beforeId", attempt.attemptId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(0));
        mvc.perform(get("/api/download/tasks/history").param("beforeTime", "bad").param("beforeId", attempt.attemptId().toString()))
                .andExpect(status().isBadRequest());
        when(identities.resolve(any())).thenReturn(RequestOwnerIdentity.owner("other"));
        mvc.perform(get("/api/download/tasks/history")).andExpect(status().isForbidden());
        verify(tasks, times(2)).history(any(), anyInt(), any());
    }
    @Test @DisplayName("使用可信身份及独立凭据提交，快照和取消返回机器可读结果")
    void submitSnapshotAndCancel() throws Exception {
        var tasks = mock(DownloadTasks.class);
        var identities = mock(RequestOwnerIdentityResolver.class);
        var admin = RequestOwnerIdentity.adminScope();
        when(identities.resolve(any())).thenReturn(admin);
        var command = new DownloadSubmission(UUID.randomUUID(), "example", "opaque/id", Map.of());
        var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "opaque/id");
        var task = new DownloadTaskSnapshot(attempt, "example", "Title", DownloadEvent.Phase.QUEUED, Instant.EPOCH, 1);
        when(tasks.submit(command, "private-credential", admin)).thenReturn(new DownloadTasks.Receipt(task, false));
        when(tasks.snapshot(admin)).thenReturn(new DownloadTasks.Snapshot(UUID.randomUUID(), 1, List.of(task)));
        when(tasks.cancel(attempt.attemptId(), admin)).thenReturn(DownloadTasks.CancelResult.REQUESTED);
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(tasks, identities, HostTestMessages.messages())).build();
        String json = JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(command);
        mvc.perform(post("/api/download/tasks").locale(Locale.US).contentType(MediaType.APPLICATION_JSON)
                .header("X-Acquisition-Credential", "private-credential").content(json))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.task.attempt.attemptId").value(attempt.attemptId().toString()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/download/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].phase").value("QUEUED"));
        mvc.perform(post("/api/download/tasks/" + attempt.attemptId() + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("REQUESTED"));
        when(tasks.submit(command, "private-credential", admin)).thenThrow(new DownloadTaskException(DownloadTaskException.Code.CONFLICT));
        mvc.perform(post("/api/download/tasks").locale(Locale.US).contentType(MediaType.APPLICATION_JSON)
                .header("X-Acquisition-Credential", "private-credential").content(json))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOWNLOAD_TASK_CONFLICT"));
    }

    @Test @DisplayName("未授权身份被拒绝，非法命令与任务标识不泄露原始异常")
    void forbiddenAndInvalidRequests() throws Exception {
        var tasks = mock(DownloadTasks.class);
        var identities = mock(RequestOwnerIdentityResolver.class);
        when(identities.resolve(any())).thenReturn(RequestOwnerIdentity.owner("visitor"));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(tasks, identities, HostTestMessages.messages())).build();
        mvc.perform(get("/api/download/tasks").locale(Locale.US)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOWNLOAD_TASK_FORBIDDEN"));
        mvc.perform(post("/api/download/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        when(identities.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());
        mvc.perform(post("/api/download/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOWNLOAD_TASK_INVALID"));
        String command = "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"workType\":\"example\",\"workId\":\"1\",\"options\":{}}";
        for (String body : List.of(command + "{}", command.replace("\"workId\":\"1\"", "\"workId\":\"1\",\"workId\":\"2\""))) {
            mvc.perform(post("/api/download/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/download/tasks").contentType(MediaType.APPLICATION_JSON).content(" ".repeat(128 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/download/tasks/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOWNLOAD_TASK_INVALID"));
        verifyNoInteractions(tasks);
    }

    @Test @DisplayName("程序 HTTP 回执使用密钥 owner，不借用管理员查询范围")
    void programUsesItsOwnTaskScope() throws Exception {
        var tasks = mock(DownloadTasks.class);
        var identities = mock(RequestOwnerIdentityResolver.class);
        var program = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(UUID.randomUUID(),
                java.util.Set.of("core:downloads.create", "example:downloads.create", "core:downloads.read"));
        var owner = RequestOwnerIdentity.owner(program.id().toString());
        var command = new DownloadSubmission(UUID.randomUUID(), "example", "opaque/id", Map.of());
        var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "opaque/id");
        var task = new DownloadTaskSnapshot(attempt, "example", "Title", DownloadEvent.Phase.QUEUED, Instant.EPOCH, 1);
        when(tasks.submitForProgram(command, null, program)).thenReturn(new DownloadTasks.Receipt(task, false));
        when(tasks.snapshot(owner)).thenReturn(new DownloadTasks.Snapshot(UUID.randomUUID(), 1, List.of(task)));
        when(tasks.cancel(attempt.attemptId(), owner)).thenReturn(DownloadTasks.CancelResult.REQUESTED);
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTasksController(tasks, identities, HostTestMessages.messages())).build();
        String attribute = top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE;
        mvc.perform(post("/api/download/tasks").requestAttr(attribute, program).contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(command)))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/download/tasks").requestAttr(attribute, program))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks[0].attempt.attemptId").value(attempt.attemptId().toString()));
        mvc.perform(post("/api/download/tasks/" + attempt.attemptId() + "/cancel").requestAttr(attribute, program))
                .andExpect(status().isOk());
        verify(tasks).snapshot(owner);
        verify(tasks).cancel(attempt.attemptId(), owner);
        verify(tasks, never()).submit(any(), any(), any());
        verifyNoInteractions(identities);
    }
}
