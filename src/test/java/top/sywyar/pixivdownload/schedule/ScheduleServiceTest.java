package top.sywyar.pixivdownload.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.NovelAutoTranslateService;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskDatabase;
import top.sywyar.pixivdownload.schedule.db.ScheduledTaskMapper;
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
    private ScheduledTaskDatabase database;
    @Mock
    private ScheduledTaskMapper mapper;
    @Mock
    private ScheduleExecutor executor;
    @Mock
    private ScheduleRunQueue runQueue;
    @Mock
    private NovelAutoTranslateService novelAutoTranslateService;

    private ScheduleService newService() {
        return new ScheduleService(database, executor, new ScheduleConfig(), new ScheduleRunState(),
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
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findByAccountId("12345"))
                .thenReturn(List.of(task(1L, "12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "999000")));
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_IGNORE);

        newService().resumeAccount("12345", req);

        verify(mapper).updateAckWarning("12345", 999000L);
        verify(mapper).clearSuspendForAccount(eq("12345"), anyLong());
    }

    @Test
    @DisplayName("账号级 resume(defer)：minutes<60 抛异常、不清挂起")
    void accountResumeDeferRejectsBelowMin() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findByAccountId("12345"))
                .thenReturn(List.of(task(1L, "12345", ScheduledTask.STATUS_OVERUSE_PAUSED, "999000")));
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_DEFER);
        req.setMinutes(30);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resumeAccount("12345", req))
                .isInstanceOf(LocalizedException.class);
        verify(mapper, never()).clearSuspendForAccount(anyString(), anyLong());
    }

    @Test
    @DisplayName("账号级 resume：账号下无任务 → 抛异常")
    void accountResumeRejectsUnknownAccount() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findByAccountId("nope")).thenReturn(List.of());
        AccountResumeRequest req = new AccountResumeRequest();
        req.setMode(AccountResumeRequest.MODE_IGNORE);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resumeAccount("nope", req))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("pause：写 DB last_status=PAUSED 并对运行中的 Claim 发协作式取消信号")
    void pauseSetsStatusAndRequestsCancel() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(42L)).thenReturn(task(42L, null, null, null));

        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = new ScheduleService(
                database, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);
        // 模拟任务正在跑：先挂一个 Claim，pause 后该 Claim 应被标为待取消
        ScheduleRunState.Claim claim = runState.tryMarkQueued(42L);
        assertThat(claim).isNotNull();
        assertThat(runState.isCancelRequested(42L)).isFalse();

        service.pause(42L);

        verify(mapper).setStatus(42L, ScheduledTask.STATUS_PAUSED);
        assertThat(runState.isCancelRequested(42L)).isTrue();
    }

    @Test
    @DisplayName("pause：任务空闲（无 Claim）→ 拒绝，不写 PAUSED（空闲任务应改用「停用」）")
    void pauseRejectedWhenIdle() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(99L)).thenReturn(task(99L, null, null, null));

        ScheduleRunState runState = new ScheduleRunState();
        ScheduleService service = new ScheduleService(
                database, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.pause(99L)).isInstanceOf(LocalizedException.class);
        verify(mapper, never()).setStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("manualRun：运行 / 排队中拒绝，不触发执行")
    void manualRunRejectedWhenBusy() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(7L)).thenReturn(task(7L, null, null, null));
        ScheduleRunState runState = new ScheduleRunState();
        runState.tryMarkQueued(7L);
        ScheduleService service = new ScheduleService(
                database, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.manualRun(7L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("manualRun：已停用任务拒绝")
    void manualRunRejectedWhenDisabled() {
        when(database.mapper()).thenReturn(mapper);
        ScheduledTask disabled = new ScheduledTask(8L, "t", false, ScheduledTaskType.USER_NEW, "{}",
                ScheduledTask.TRIGGER_INTERVAL, 60, null, ScheduledTask.COOKIE_BOUND, null,
                1000L, null, null, null, null, null, null, null, 0, 0L);
        when(mapper.findById(8L)).thenReturn(disabled);

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.manualRun(8L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("manualRun：暂停 / 挂起态任务拒绝")
    void manualRunRejectedWhenSuspended() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(9L)).thenReturn(task(9L, null, ScheduledTask.STATUS_PAUSED, null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.manualRun(9L)).isInstanceOf(LocalizedException.class);
        verify(executor, never()).runTaskAsync(anyLong(), any());
    }

    @Test
    @DisplayName("authorizeCookie：Cookie 与现有快照一致 → 拒绝，不写 Cookie、不解析账号、不清 AUTH_EXPIRED")
    void authorizeCookieRejectedWhenUnchanged() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(5L)).thenReturn(task(5L, "12345", ScheduledTask.STATUS_AUTH_EXPIRED, null));
        when(mapper.findCookieSnapshot(5L)).thenReturn("PHPSESSID=12345_abc; other=x");

        ScheduleService service = newService();
        // 提交前后空白不同，但去空白后与现有快照一致 → 仍判为「未变化」
        assertThatThrownBy(() -> service.authorizeCookie(5L, "  PHPSESSID=12345_abc; other=x  "))
                .isInstanceOf(LocalizedException.class);

        verify(mapper, never()).updateCookie(anyLong(), anyString(), anyString());
        verify(mapper, never()).updateAccountId(anyLong(), any());
        verify(mapper, never()).clearSuspendIfStatus(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("authorizeCookie：Cookie 与现有快照不同 → 写 Cookie、解析账号、清 AUTH_EXPIRED")
    void authorizeCookieSavesWhenChanged() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(6L)).thenReturn(task(6L, "12345", ScheduledTask.STATUS_AUTH_EXPIRED, null));
        when(mapper.findCookieSnapshot(6L)).thenReturn("PHPSESSID=12345_old; other=x");

        newService().authorizeCookie(6L, "PHPSESSID=12345_new; other=x");

        verify(mapper).updateCookie(6L, "PHPSESSID=12345_new; other=x", ScheduledTask.COOKIE_BOUND);
        verify(mapper).updateAccountId(6L, "12345");
        verify(mapper).clearSuspendIfStatus(eq(6L), any(), eq(ScheduledTask.STATUS_AUTH_EXPIRED));
    }

    @Test
    @DisplayName("authorizeCookie：任务尚无 Cookie 快照（首次授权）→ 正常写入")
    void authorizeCookieSavesWhenNoExisting() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(7L)).thenReturn(task(7L, null, null, null));
        when(mapper.findCookieSnapshot(7L)).thenReturn(null);

        newService().authorizeCookie(7L, "PHPSESSID=999_abc");

        verify(mapper).updateCookie(7L, "PHPSESSID=999_abc", ScheduledTask.COOKIE_BOUND);
    }

    @Test
    @DisplayName("updateProxy：合法 host:port 去空白后写入任务级单独代理")
    void updateProxySavesValidHostPort() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(11L)).thenReturn(task(11L, null, null, null));

        newService().updateProxy(11L, " 127.0.0.1:7890 ");

        verify(mapper).updateProxy(11L, "127.0.0.1:7890");
    }

    @Test
    @DisplayName("updateProxy：格式非法（缺端口 / 端口越界）直接拒绝、不写库")
    void updateProxyRejectsInvalidFormat() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(12L)).thenReturn(task(12L, null, null, null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1"))
                .isInstanceOf(LocalizedException.class);
        assertThatThrownBy(() -> service.updateProxy(12L, "127.0.0.1:0"))
                .isInstanceOf(LocalizedException.class);
        verify(mapper, never()).updateProxy(anyLong(), anyString());
    }

    @Test
    @DisplayName("updateProxy：空值清除单独代理（回退全局代理设置）")
    void updateProxyClearsWhenBlank() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(13L)).thenReturn(task(13L, null, null, null));

        newService().updateProxy(13L, "  ");

        verify(mapper).updateProxy(13L, null);
    }

    @Test
    @DisplayName("resume：非手动暂停态任务拒绝恢复")
    void resumeRejectedWhenNotPaused() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(10L)).thenReturn(task(10L, null, "OK", null));

        ScheduleService service = newService();
        assertThatThrownBy(() -> service.resume(10L)).isInstanceOf(LocalizedException.class);
        verify(mapper, never()).clearSuspend(anyLong(), anyLong());
    }

    @Test
    @DisplayName("queue：仅对本轮确实提交过自动翻译的小说条目叠加翻译状态，跳过 / 未提交条目不读旧状态")
    void queueOverlaysTranslateOnlyForSubmittedItems() {
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(1L)).thenReturn(task(1L, null, null, null));

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
        when(database.mapper()).thenReturn(mapper);
        when(mapper.findById(11L)).thenReturn(task(11L, null, null, null));
        ScheduleRunState runState = new ScheduleRunState();
        runState.tryMarkRunning(11L);
        ScheduleService service = new ScheduleService(
                database, executor, new ScheduleConfig(), runState, runQueue, novelAutoTranslateService);

        assertThatThrownBy(() -> service.delete(11L)).isInstanceOf(LocalizedException.class);
        verify(mapper, never()).delete(anyLong());
    }
}
