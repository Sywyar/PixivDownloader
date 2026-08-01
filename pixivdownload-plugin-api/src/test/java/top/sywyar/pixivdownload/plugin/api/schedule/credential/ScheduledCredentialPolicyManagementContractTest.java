package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划凭证策略管理纯值契约")
class ScheduledCredentialPolicyManagementContractTest {

    @Test
    @DisplayName("旧策略默认不声明任务展示、账号动作或事件通知")
    void policyDefaultsRemainEmptyAndCompatible() throws Exception {
        ScheduledCredentialPolicy policy = policy();
        ScheduledCredentialTaskSnapshot task = task(1L);
        ScheduledCredentialAccountActionRequest action = new ScheduledCredentialAccountActionRequest(
                "account-1", "ignore", Map.of(), 1_000L, List.of(task));
        ScheduledCredentialAccountIncident incident = new ScheduledCredentialAccountIncident(
                "account-1", "UPSTREAM_WARNING", ScheduledGuardEvidence.empty(),
                1_000L, List.of(task));

        assertThat(policy.taskPresentation(task))
                .isEqualTo(ScheduledCredentialTaskPresentation.empty());
        assertThat(policy.prepareAccountAction(action)).isEmpty();
        assertThat(policy.incidentPresentation(incident))
                .isEqualTo(ScheduledCredentialIncidentPresentation.empty());
        for (String methodName : List.of(
                "taskPresentation", "prepareAccountAction", "incidentPresentation")) {
            assertThat(java.util.Arrays.stream(ScheduledCredentialPolicy.class.getMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .map(Method::isDefault))
                    .containsExactly(true);
        }
    }

    @Test
    @DisplayName("账号恢复计划保存精确 CAS 身份并防御性复制安全值")
    void accountActionPlanKeepsExactCasIdentityAndImmutableValues() {
        ScheduledCredentialTaskSnapshot task = task(7L);
        Map<String, String> parameters = new LinkedHashMap<>(Map.of("minutes", "120"));
        List<ScheduledCredentialTaskSnapshot> tasks = new ArrayList<>(List.of(task));
        ScheduledCredentialAccountActionRequest request =
                new ScheduledCredentialAccountActionRequest(
                        " account-7 ", "defer", parameters, 2_000L, tasks);
        ScheduledCredentialTaskStateUpdate update = new ScheduledCredentialTaskStateUpdate(
                7L, 3L, "{\"ack\":900}", "{\"ack\":1000}");
        List<ScheduledCredentialTaskStateUpdate> updates = new ArrayList<>(List.of(update));
        ScheduledCredentialAccountActionPlan plan =
                new ScheduledCredentialAccountActionPlan(
                        "UPSTREAM_WARNING", 3_600_000L, updates);

        parameters.clear();
        tasks.clear();
        updates.clear();

        assertThat(request.accountKey()).isEqualTo("account-7");
        assertThat(request.parameters()).containsExactlyEntriesOf(Map.of("minutes", "120"));
        assertThat(request.tasks()).containsExactly(task);
        assertThat(plan.stateUpdates()).containsExactly(update);
        assertThat(update.expectedStateVersion()).isEqualTo(3L);
        assertThat(update.expectedPolicyStateJson()).isEqualTo("{\"ack\":900}");
        assertThatThrownBy(() -> request.parameters().put("late", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.tasks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.stateUpdates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("任务状态与账号事件只投影受控机器码、标量和时间参数")
    void taskAndIncidentPresentationsKeepSafeTypedValues() {
        ScheduledCredentialTaskPresentation taskPresentation =
                new ScheduledCredentialTaskPresentation("OVERUSE_PAUSED", 900L);
        Map<String, String> scalar = new LinkedHashMap<>(Map.of("excerpt", "safe warning"));
        Map<String, Long> times = new LinkedHashMap<>(Map.of("warning_time", 1_000L));
        ScheduledCredentialIncidentPresentation incident =
                new ScheduledCredentialIncidentPresentation(
                        "overuse-paused", scalar, times);

        scalar.clear();
        times.clear();

        assertThat(taskPresentation.statusCode()).isEqualTo("OVERUSE_PAUSED");
        assertThat(taskPresentation.acknowledgedEventTime()).isEqualTo(900L);
        assertThat(incident.scalarAttributes()).containsEntry("excerpt", "safe warning");
        assertThat(incident.timeAttributes()).containsEntry("warning_time", 1_000L);
        assertThatThrownBy(() -> incident.scalarAttributes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> incident.timeAttributes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("全部新值对象拒绝凭证材料、重复任务与越界机器态")
    void managementValuesRejectCredentialMaterialAndAmbiguousPlans() {
        ScheduledCredentialTaskSnapshot task = task(11L);

        assertThatThrownBy(() -> new ScheduledCredentialTaskSnapshot(
                11L, 3L, false, true, false, "UPSTREAM_WARNING",
                "{\"excerpt\":\"Cookie: sid=opaque\"}", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialTaskSnapshot(
                11L, 3L, false, true, false, "UPSTREAM_WARNING",
                "{}", "{\"token\":\"opaque-token-value\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialAccountActionRequest(
                "Cookie: sid=opaque", "ignore", Map.of(), 1L, List.of(task)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialAccountActionRequest(
                "account-1", "ignore", Map.of("accessToken", "opaque"),
                1L, List.of(task)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialAccountActionRequest(
                "account-1", "ignore", Map.of(), 1L, List.of(task, task)))
                .isInstanceOf(IllegalArgumentException.class);

        ScheduledCredentialTaskStateUpdate update = new ScheduledCredentialTaskStateUpdate(
                11L, 3L, "{}", "{\"ack\":1000}");
        assertThatThrownBy(() -> new ScheduledCredentialTaskStateUpdate(
                11L, 3L, "{}", "{\"secret\":\"opaque-value\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialAccountActionPlan(
                "UPSTREAM_WARNING", 1L, List.of(update, update)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialTaskPresentation(
                "cookie", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialTaskPresentation(
                "OVERUSE_PAUSED", -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialIncidentPresentation(
                "overuse-paused", Map.of("excerpt", "Authorization: Bearer opaque"),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialIncidentPresentation(
                "overuse-paused", Map.of(), Map.of("warning_time", -1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialIncidentPresentation(
                "overuse-paused", Map.of("warning_time", "safe"),
                Map.of("warning_time", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("账号、任务状态、任务集合与通知参数都执行确定性大小上限")
    void managementValuesEnforceDeterministicSizeLimits() {
        ScheduledCredentialTaskSnapshot task = task(21L);
        List<ScheduledCredentialTaskSnapshot> tooManyTasks = LongStream.rangeClosed(
                        1L, ScheduledCredentialAccountActionRequest.MAX_TASKS + 1L)
                .mapToObj(ScheduledCredentialPolicyManagementContractTest::task)
                .toList();
        Map<String, String> scalar = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) {
            scalar.put("scalar" + index, "safe");
        }
        for (int index = 0; index < 9; index++) {
            times.put("time" + index, (long) index);
        }

        assertThatThrownBy(() -> new ScheduledCredentialAccountActionRequest(
                "a".repeat(ScheduledCredentialAccountActionRequest.MAX_ACCOUNT_KEY_BYTES + 1),
                "ignore", Map.of(), 1L, List.of(task)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialAccountActionRequest(
                "account-1", "ignore", Map.of(), 1L, tooManyTasks))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialTaskStateUpdate(
                21L, 3L, "{}",
                "x".repeat(ScheduledCredentialTaskSnapshot.MAX_POLICY_STATE_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialTaskPresentation(
                "S".repeat(ScheduledCredentialTaskSnapshot.MAX_CODE_BYTES + 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialIncidentPresentation(
                "overuse-paused", scalar, times))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScheduledCredentialPolicy policy() {
        return new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return "fixture:credential";
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                return ScheduledCredentialProbeResult.valid("account-1");
            }
        };
    }

    private static ScheduledCredentialTaskSnapshot task(long taskId) {
        return new ScheduledCredentialTaskSnapshot(
                taskId,
                3L,
                false,
                true,
                false,
                "UPSTREAM_WARNING",
                "{\"modifiedAt\":1000,\"excerpt\":\"safe warning\"}",
                "{\"ack\":900}");
    }
}
