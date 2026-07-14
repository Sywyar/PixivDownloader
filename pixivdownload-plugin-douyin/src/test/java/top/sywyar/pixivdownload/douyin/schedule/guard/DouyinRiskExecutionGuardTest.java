package top.sywyar.pixivdownload.douyin.schedule.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.schedule.failure.DouyinScheduledFailureMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("抖音计划风险 Guard")
class DouyinRiskExecutionGuardTest {

    @Test
    @DisplayName("运行失败按凭据挑战限流与访问分类返回宿主动作")
    void failuresMapToStableHostActions() throws Exception {
        Map<ScheduledFailure.Category, ScheduledGuardDecision.Action> expected = Map.of(
                ScheduledFailure.Category.CREDENTIAL_INVALID,
                ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                ScheduledFailure.Category.CHALLENGE,
                ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                ScheduledFailure.Category.RATE_LIMITED,
                ScheduledGuardDecision.Action.RETRY_LATER,
                ScheduledFailure.Category.ACCESS_UNAVAILABLE,
                ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK);
        DouyinRiskExecutionGuard guard = new DouyinRiskExecutionGuard();

        for (Map.Entry<ScheduledFailure.Category, ScheduledGuardDecision.Action> entry
                : expected.entrySet()) {
            long delay = entry.getKey() == ScheduledFailure.Category.RATE_LIMITED ? 12_345L : 0L;
            ScheduledGuardDecision decision = guard.evaluate(context(
                    ScheduledGuardPoint.RUN_FAILURE,
                    new ScheduledFailure(entry.getKey(), "safe.code", delay)));
            assertThat(decision.action()).as(entry.getKey().name()).isEqualTo(entry.getValue());
            if (entry.getKey() == ScheduledFailure.Category.RATE_LIMITED) {
                assertThat(decision.retryAfterMillis()).isEqualTo(12_345L);
            }
        }
    }

    @Test
    @DisplayName("无重试时间的限流使用固定退避且普通失败继续交由宿主处理")
    void rateLimitFallbackAndOrdinaryFailureProceed() throws Exception {
        DouyinRiskExecutionGuard guard = new DouyinRiskExecutionGuard();

        ScheduledGuardDecision limited = guard.evaluate(context(
                ScheduledGuardPoint.RUN_FAILURE,
                new ScheduledFailure(ScheduledFailure.Category.RATE_LIMITED,
                        "safe.rate", 0L)));
        ScheduledGuardDecision notFound = guard.evaluate(context(
                ScheduledGuardPoint.RUN_FAILURE,
                new ScheduledFailure(ScheduledFailure.Category.NOT_FOUND,
                        "safe.not-found", 0L)));

        assertThat(limited.action()).isEqualTo(ScheduledGuardDecision.Action.RETRY_LATER);
        assertThat(limited.retryAfterMillis())
                .isEqualTo(DouyinScheduledFailureMapper.RATE_LIMIT_RETRY_MILLIS);
        assertThat(notFound.action()).isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
    }

    @Test
    @DisplayName("非失败检查点不读取失败内容并直接继续")
    void nonFailurePointsAlwaysProceed() throws Exception {
        DouyinRiskExecutionGuard guard = new DouyinRiskExecutionGuard();

        for (ScheduledGuardPoint point : new ScheduledGuardPoint[]{
                ScheduledGuardPoint.RUN_START,
                ScheduledGuardPoint.WORK_BATCH,
                ScheduledGuardPoint.RUN_END}) {
            assertThat(guard.evaluate(context(point, null)).action())
                    .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        }
    }

    private static ScheduledGuardContext context(
            ScheduledGuardPoint point,
            ScheduledFailure failure) {
        ScheduledGuardContext context = mock(ScheduledGuardContext.class);
        when(context.point()).thenReturn(point);
        when(context.failure()).thenReturn(failure);
        return context;
    }
}
