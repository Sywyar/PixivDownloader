package top.sywyar.pixivdownload.schedule.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划任务保存边界严格校验")
class ScheduleTaskDefinitionValidatorTest {

    private static final String SOURCE_TYPE = "fixture:source";
    private static final String SCHEMA = "fixture.definition";

    private final ScheduleTaskDefinitionValidator validator =
            new ScheduleTaskDefinitionValidator(new ObjectMapper());

    @Test
    @DisplayName("只接受一个无重复字段的 JSON 对象")
    void acceptsExactlyOneJsonObjectWithoutDuplicateFields() {
        ScheduledTaskDefinition accepted = validator.validatePrepared(
                definition("{\"nested\":{\"value\":1}}"),
                8L, SOURCE_TYPE, SCHEMA, 1);

        assertThat(accepted.definitionJson()).isEqualTo("{\"nested\":{\"value\":1}}");
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"value\":1,\"value\":2}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"value\":1} {\"other\":2}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("[1,2]"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);

        ScheduledTaskDefinition ordinaryText = validator.validatePrepared(
                definition("{\"word\":\"{foo\"}"),
                8L, SOURCE_TYPE, SCHEMA, 1);
        assertThat(ordinaryText.definitionJson()).contains("{foo");
    }

    @Test
    @DisplayName("定义和展示拒绝 NUL、非法代理项与凭证材料")
    void rejectsUnsafeUnicodeAndCredentialMaterial() {
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"value\":\"\\u0000\"}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"value\":\"\\uD800\"}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"nested\":{\"refresh_token\":\"value\"}}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"opaque\":\"{\\\"cookie\\\":\\\"a=b\\\"}\"}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                definition("{\"opaque\":\"{\\\"value\\\":1,\\\"value\\\":2}\"}"),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);

        ScheduledTaskDefinition invalidPresentation = new ScheduledTaskDefinition(
                8L, SOURCE_TYPE, SCHEMA, 1, "{}",
                new ScheduledTaskPresentation("\uD800", null, Map.of()));
        assertThatThrownBy(() -> validator.validatePrepared(
                invalidPresentation, 8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);

        String deeplyEmbedded = "{\"cookie\":\"a=b\"}";
        try {
            for (int depth = 0; depth < 18; depth++) {
                deeplyEmbedded = new ObjectMapper().writeValueAsString(Map.of("value", deeplyEmbedded));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
        String nestedCredential = deeplyEmbedded;
        assertThatThrownBy(() -> validator.validatePrepared(
                definition(nestedCredential), 8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("插件不能修改宿主盖章的任务、来源、schema 或版本")
    void rejectsChangedHostStampedFields() {
        assertThatThrownBy(() -> validator.validatePrepared(
                new ScheduledTaskDefinition(
                        9L, SOURCE_TYPE, SCHEMA, 1, "{}", ScheduledTaskPresentation.empty()),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                new ScheduledTaskDefinition(
                        8L, "other:source", SCHEMA, 1, "{}", ScheduledTaskPresentation.empty()),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePrepared(
                new ScheduledTaskDefinition(
                        8L, SOURCE_TYPE, "other.schema", 2, "{}", ScheduledTaskPresentation.empty()),
                8L, SOURCE_TYPE, SCHEMA, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("执行计划不能越过来源声明的作品、凭证策略和 Guard 能力")
    void rejectsExecutionPlanCapabilityEscapes() {
        ScheduledSourceDescriptor descriptor = descriptor();

        validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                "fixture:credential",
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        "fixture:guard", Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null, 0, 1, 0L));

        assertThatThrownBy(() -> validator.validatePlan(
                descriptor, ScheduledExecutionPlan.credentialFree(Set.of("other:work"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                "other:credential",
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(), null, 0, 1, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                null,
                ScheduledCredentialRequirement.NONE,
                false,
                List.of(new ScheduledGuardBinding(
                        "other:guard", Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null, 0, 1, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("保存期计划 gate 拒绝空计划、过大并发、过大批次与重复 Guard")
    void rejectsPlanResourceBoundsAndDuplicateGuards() {
        ScheduledSourceDescriptor descriptor = descriptor();
        ScheduledGuardBinding normal = new ScheduledGuardBinding(
                "fixture:guard", Set.of(ScheduledGuardPoint.RUN_START), 0);

        assertThatThrownBy(() -> validator.validatePlan(descriptor, null))
                .isInstanceOf(ScheduleExecutionPlanGate.Violation.class);
        assertThatThrownBy(() -> validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                null,
                ScheduledCredentialRequirement.NONE,
                false,
                List.of(), null, 0, 257, 0L)))
                .isInstanceOf(ScheduleExecutionPlanGate.Violation.class);
        assertThatThrownBy(() -> validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                null,
                ScheduledCredentialRequirement.NONE,
                false,
                List.of(new ScheduledGuardBinding(
                        "fixture:guard", Set.of(ScheduledGuardPoint.WORK_BATCH), 100_001)),
                null, 0, 1, 0L)))
                .isInstanceOf(ScheduleExecutionPlanGate.Violation.class);
        assertThatThrownBy(() -> validator.validatePlan(descriptor, new ScheduledExecutionPlan(
                Set.of("fixture:work"),
                null,
                ScheduledCredentialRequirement.NONE,
                false,
                List.of(normal, normal), null, 0, 1, 0L)))
                .isInstanceOf(ScheduleExecutionPlanGate.Violation.class);
    }

    private static ScheduledTaskDefinition definition(String json) {
        return new ScheduledTaskDefinition(
                8L,
                SOURCE_TYPE,
                SCHEMA,
                1,
                json,
                new ScheduledTaskPresentation("任务", null, Map.of("kind", "fixture")));
    }

    private static ScheduledSourceDescriptor descriptor() {
        return new ScheduledSourceDescriptor(
                SOURCE_TYPE,
                Set.of(),
                SCHEMA,
                1,
                new ScheduledSourcePresentation(
                        "fixture", "fixture.name", "fixture.description", "schedule", "neutral"),
                Set.of("fixture"),
                Set.of("fixture:work"),
                Set.of("fixture:credential"),
                Set.of("fixture:guard"),
                null);
    }
}
