package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;
import top.sywyar.pixivdownload.schedule.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService 暂停恢复与账号解析")
class ScheduleServiceTest {

    @Mock
    private ScheduledTaskStore store;
    @Mock
    private ScheduleExecutor executor;
    @Mock
    private ScheduleRunQueue runQueue;
    @Mock
    private NovelAutoTranslateService novelAutoTranslateService;

    private ScheduleService newService() {
        return new ScheduleService(store, executor, new ScheduleConfig(), new ScheduleRunState(),
                runQueue, novelAutoTranslateService);
    }

    private static ScheduledTask task(long id, String accountId, String status, String message) {
        return new ScheduledTask(id, "t", true, ScheduledTaskType.USER_NEW, "{}",
                ScheduledTask.TRIGGER_INTERVAL, 60, null, ScheduledTask.COOKIE_BOUND, null,
                1000L, null, status, message, null, null, accountId, null, 0, 0L);
    }

    @Test
    @DisplayName("parsePixivUserId：标准 PHPSESSID 取下划线前缀 userId")
    void parsesStandardPhpsessid() {
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=12345_abcdefghijklmnop; other=x"))
                .isEqualTo("12345");
    }

    @Test
    @DisplayName("parsePixivUserId：无 PHPSESSID / 无下划线 / 前缀非数字 → null")
    void parsesNullForMalformed() {
        assertThat(ScheduleService.parsePixivUserId("foo=bar; baz=qux")).isNull();
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=abcdefonly")).isNull();
        assertThat(ScheduleService.parsePixivUserId("PHPSESSID=abc_def")).isNull();
        assertThat(ScheduleService.parsePixivUserId(null)).isNull();
    }

    @Test
    @DisplayName("账号级 resume(ignore)：写 ack(触发 modifiedAt) 并 clearSuspendForAccount(now)")
    void accountResumeIgnore() {
        when(store.findByAccountId("12345"))
                .thenReturn(List.of(task(1L, "12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "999000")));
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_IGNORE);

        newService().resumeAccount("12345", req);

        verify(store).updateAckWarning("12345", 999000L);
        verify(store).clearSuspendForAccount(eq("12345"), anyLong());
    }

    @Test
    @DisplayName("账号级 resume(defer)：minutes<60 抛异常、不清挂起")
    void accountResumeDeferRejectsBelowMin() {
        when(store.findByAccountId("12345"))
                .thenReturn(List.of(task(1L, "12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "999000")));
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_DEFER);
        req.setMinutes(30);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resumeAccount("12345", req))
                .isInstanceOf(LocalizedException.class);
        verify(store, never()).clearSuspendForAccount(anyString(), anyLong());
    }

    @Test
    @DisplayName("账号级 resume：账号下无任务 → 抛异常")
    void accountResumeRejectsUnknownAccount() {
        when(store.findByAccountId("nope")).thenReturn(List.of());
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_IGNORE);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resumeAccount("nope", req))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("pause：写 DB last_status=PAUSED 并对运行中的 Claim 发协作式取消信号")
    void pauseSetsStatusAndRequestsCancel() {
        when(store.findById(42L)).thenReturn(task(42L, null, null, null));

        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);
        // 模拟任务正在跑：先挂一个 Claim，pause 后该 Claim 应被标为待取消
        ScheduleRunState.Claim claim = runState.tryMarkQueued(42L);
        assertThat(claim).isNotNull();
        assertThat(runState.isCancelRequested(42L)).isFalse();

        service.pause(42L);

        verify(store).setStatus(42L, ScheduledTask.STATUS_PAUSED);
        assertThat(runState.isCancelRequested(42L)).isTrue();
    }

    @Test
    @DisplayName("pause：任务空闲（无 Claim）→ 拒绝，不写 PAUSED（空闲任务应改用「停用」）")
    void pauseRejectedWhenIdle() {
        when(store.findById(99L)).thenReturn(task(99L, null, null, null));

        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.pause(99L)).isInstanceOf(LocalizedException.class);
        verify(store, never()).setStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("manualRun：运行 / 排队中拒绝，不触发执行")
    void manualRunRejectedWhenBusy() {
        when(store.findById(7L)).thenReturn(task(7L, null, null, null));
        ScheduleRunState runState = new ScheduleRunState();
        runState.tryMarkQueued(7L);
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.manualRun(7L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("manualRun：已停用任务拒绝")
    void manualRunRejectedWhenDisabled() {
        ScheduledTask disabled = new ScheduledTask(8L, "t", false, ScheduledTaskType.USER_NEW, "{}",
                ScheduledTask.TRIGGER_INTERVAL, 60, null, ScheduledTask.COOKIE_BOUND, null,
                1000L, null, null, null, null, null, null, null, 0, 0L);
        when(store.findById(8L)).thenReturn(disabled);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.manualRun(8L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("manualRun：暂停 / 挂起态任务拒绝")
    void manualRunRejectedWhenSuspended() {
        when(store.findById(9L)).thenReturn(task(9L, null, ScheduledTask.STATUS_PAUSED, null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.manualRun(9L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("authorizeCookie：Cookie 与现有快照一致 → 拒绝，不写 Cookie、不解析账号、不清 AUTH_EXPIRED")
    void authorizeCookieRejectedWhenUnchanged() {
        when(store.findById(5L)).thenReturn(task(5L, "12345", ScheduledTask.STATUS_AUTH_EXPIRED, null));
        when(store.findCookieSnapshot(5L)).thenReturn("PHPSESSID=12345_abc; other=x");

        ScheduleService service = newService();
        // 提交前后空白不同，但去空白后与现有快照一致 → 仍判为「未变化」
        assertThatThrownBy(() -> service.authorizeCookie(5L, "  PHPSESSID=12345_abc; other=x  "))
                .isInstanceOf(LocalizedException.class);

        verify(store, never()).updateCookie(anyLong(), anyString(), anyString());
        verify(store, never()).updateAccountId(anyLong(), any());
        verify(store, never()).clearSuspendIfStatus(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("authorizeCookie：Cookie 与现有快照不同 → 写 Cookie、解析账号、清 AUTH_EXPIRED")
    void authorizeCookieSavesWhenChanged() {
        when(store.findById(6L)).thenReturn(task(6L, "12345", ScheduledTask.STATUS_AUTH_EXPIRED, null));
        when(store.findCookieSnapshot(6L)).thenReturn("PHPSESSID=12345_old; other=x");

        newService().authorizeCookie(6L, "PHPSESSID=12345_new; other=x");

        verify(store).updateCookie(6L, "PHPSESSID=12345_new; other=x", ScheduledTask.COOKIE_BOUND);
        verify(store).updateAccountId(6L, "12345");
        verify(store).clearSuspendIfStatus(eq(6L), any(), eq(ScheduledTask.STATUS_AUTH_EXPIRED));
    }

    @Test
    @DisplayName("authorizeCookie：任务尚无 Cookie 快照（首次授权）→ 正常写入")
    void authorizeCookieSavesWhenNoExisting() {
        when(store.findById(7L)).thenReturn(task(7L, null, null, null));
        when(store.findCookieSnapshot(7L)).thenReturn(null);

        newService().authorizeCookie(7L, "PHPSESSID=999_abc");

        verify(store).updateCookie(7L, "PHPSESSID=999_abc", ScheduledTask.COOKIE_BOUND);
    }

    @Test
    @DisplayName("revokeCookie：清 Cookie 转受限的同时清除账号绑定（account_id / ack_warning_time），不走普通 updateCookie")
    void revokeCookieClearsAccountBinding() {
        when(store.findById(20L)).thenReturn(task(20L, "12345", null, null));

        newService().revokeCookie(20L);

        verify(store).clearCookieAndAccount(20L, ScheduledTask.COOKIE_RESTRICTED);
        // 不能再用「仅清 cookie」的旧路径——那会残留 account_id 让任务仍被同账号冻结
        verify(store, never()).updateCookie(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("updateProxy：合法 host:port 去空白后写入任务级单独代理")
    void updateProxySavesValidHostPort() {
        when(store.findById(11L)).thenReturn(task(11L, null, null, null));

        newService().updateProxy(11L, " 127.0.0.1:7890 ");

        verify(store).updateProxy(11L, "127.0.0.1:7890");
    }

    @Test
    @DisplayName("updateProxy：格式非法（缺端口 / 端口越界 / 带 scheme / 含用户名密码）直接拒绝、不写库")
    void updateProxyRejectsInvalidFormat() {
        when(store.findById(12L)).thenReturn(task(12L, null, null, null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1:0"))
                .isInstanceOf(LocalizedException.class);
        // 带 scheme / 用户名密码：会被「最后一个冒号」切出貌似合法的 host，必须拒绝
        assertThatThrownBy(() -> service.updateProxy(12L, "http://127.0.0.1:7890"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "user:pass@127.0.0.1:7890"))
                .isInstanceOf(LocalizedException.class);
        verify(store, never()).updateProxy(anyLong(), anyString());
    }

    @Test
    @DisplayName("updateProxy：空值清除单独代理（回退全局代理设置）")
    void updateProxyClearsWhenBlank() {
        when(store.findById(13L)).thenReturn(task(13L, null, null, null));

        newService().updateProxy(13L, "  ");

        verify(store).updateProxy(13L, null);
    }

    @Test
    @DisplayName("resume：非手动暂停态任务拒绝恢复")
    void resumeRejectedWhenNotPaused() {
        when(store.findById(10L)).thenReturn(task(10L, null, "OK", null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resume(10L)).isInstanceOf(LocalizedException.class);
        verify(store, never()).clearSuspend(anyLong(), anyLong());
    }

    @Test
    @DisplayName("queue：仅对本轮确实提交过自动翻译的小说条目叠加翻译状态，跳过 / 未提交条目不读旧状态")
    void queueOverlaysTranslateOnlyForSubmittedItems() {
        when(store.findById(1L)).thenReturn(task(1L, null, null, null));

        ScheduleRunQueue.Run run = ScheduleRunQueue.detachedRun(ScheduleRunQueue.KIND_NOVEL);
        // 111：本轮真正下载并提交了自动翻译 → 应叠加状态
        run.discovered("111", ScheduleRunQueue.KIND_NOVEL);
        run.mark("111", ScheduleRunQueue.STATUS_DOWNLOADED, null);
        run.markAutoTranslateSubmitted("111");
        // 222：已存在跳过（本轮未提交翻译）→ 即使该 novelId 历史译过也不得叠加旧状态
        run.discovered("222", ScheduleRunQueue.KIND_NOVEL);
        run.mark("222", ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED, null);
        when(runQueue.get(1L)).thenReturn(run);
        when(novelAutoTranslateService.getStatus(111L)).thenReturn(
                new NovelAutoTranslateService.StatusView("TRANSLATING", 5L, 0, "en-US", false, false, null));

        List<ScheduleQueueView.Item> items = newService().queue(1L).items();

        ScheduleQueueView.Item submitted = items.stream().filter(i -> i.id().equals("111")).findFirst().orElseThrow();
        ScheduleQueueView.Item skipped = items.stream().filter(i -> i.id().equals("222")).findFirst().orElseThrow();
        assertThat(submitted.translatePhase()).isEqualTo("TRANSLATING");
        assertThat(skipped.translatePhase()).isNull();
        verify(novelAutoTranslateService).getStatus(111L);
        verify(novelAutoTranslateService, never()).getStatus(222L);
    }

    @Test
    @DisplayName("结构性操作（删除）在运行 / 排队中被拒绝")
    void deleteRejectedWhenBusy() {
        when(store.findById(11L)).thenReturn(task(11L, null, null, null));
        ScheduleRunState runState = new ScheduleRunState();
        runState.tryMarkRunning(11L);
        ScheduleService service = new ScheduleService(
                store, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.delete(11L)).isInstanceOf(LocalizedException.class);
        verify(store, never()).delete(anyLong());
    }
}
