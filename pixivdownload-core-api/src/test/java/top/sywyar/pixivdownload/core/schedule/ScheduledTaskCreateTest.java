package top.sywyar.pixivdownload.core.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScheduledTaskCreate 核心创建契约")
class ScheduledTaskCreateTest {

    @Test
    @DisplayName("只保存首次创建所需的 canonical 业务字段")
    void preservesCanonicalCreateFields() {
        ScheduledTaskCreate command = create("任务", 1, "{}", "{}");

        assertThat(command.name()).isEqualTo("任务");
        assertThat(command.sourceType()).isEqualTo("fixture-source");
        assertThat(command.sourceOwnerPluginId()).isEqualTo("fixture-plugin");
        assertThat(command.definitionSchema()).isEqualTo("fixture.definition");
        assertThat(command.definitionVersion()).isEqualTo(1);
        assertThat(command.definitionJson()).isEqualTo("{}");
        assertThat(command.presentationJson()).isEqualTo("{}");
        assertThat(command.triggerKind()).isEqualTo(ScheduledTask.TRIGGER_INTERVAL);
        assertThat(command.intervalMinutes()).isEqualTo(60);
        assertThat(command.cronExpr()).isNull();
        assertThat(command.nextRunTime()).isEqualTo(2_000L);
        assertThat(command.createdTime()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("拒绝空 owner、非法定义版本与缺失 JSON")
    void rejectsInvalidCanonicalFields() {
        assertThatThrownBy(() -> new ScheduledTaskCreate(
                "任务", "fixture-source", " ", "fixture.definition", 1,
                "{}", "{}", ScheduledTask.TRIGGER_INTERVAL, 60, null, 2_000L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceOwnerPluginId");
        assertThatThrownBy(() -> create("任务", 0, "{}", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definitionVersion");
        assertThatThrownBy(() -> create("任务", 1, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definitionJson");
        assertThatThrownBy(() -> create("任务", 1, "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("presentationJson");
    }

    private static ScheduledTaskCreate create(
            String name,
            int definitionVersion,
            String definitionJson,
            String presentationJson) {
        return new ScheduledTaskCreate(
                name,
                "fixture-source",
                "fixture-plugin",
                "fixture.definition",
                definitionVersion,
                definitionJson,
                presentationJson,
                ScheduledTask.TRIGGER_INTERVAL,
                60,
                null,
                2_000L,
                1_000L);
    }
}
