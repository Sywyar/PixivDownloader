package top.sywyar.pixivdownload.plugin.api.schedule.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划来源展示 token 契约")
class ScheduledSourcePresentationTest {

    @Test
    @DisplayName("展示 token 规范化空默认值并接受精确长度上限")
    void normalizesDefaultsAndAcceptsExactLimits() {
        ScheduledSourcePresentation defaults = new ScheduledSourcePresentation(
                " example.source ",
                " source.Name ",
                " source.Description ",
                null,
                " ");

        assertThat(defaults.displayNamespace()).isEqualTo("example.source");
        assertThat(defaults.displayNameKey()).isEqualTo("source.Name");
        assertThat(defaults.descriptionKey()).isEqualTo("source.Description");
        assertThat(defaults.iconKey()).isEqualTo("schedule");
        assertThat(defaults.colorToken()).isEqualTo("neutral");

        ScheduledSourcePresentation maximum = new ScheduledSourcePresentation(
                "a" + "b".repeat(63),
                "A" + "b".repeat(191),
                "D" + "e".repeat(191),
                "i" + "c".repeat(39),
                "c" + "o".repeat(39));

        assertThat(maximum.displayNamespace()).hasSize(64);
        assertThat(maximum.displayNameKey()).hasSize(192);
        assertThat(maximum.descriptionKey()).hasSize(192);
        assertThat(maximum.iconKey()).hasSize(40);
        assertThat(maximum.colorToken()).hasSize(40);
    }

    @Test
    @DisplayName("namespace 拒绝路径控制符与越界值")
    void namespaceRejectsUnsafeOrOversizedValues() {
        for (String namespace : List.of(
                "Example",
                "example source",
                "example:source",
                "example/source",
                "example\nsource",
                "<example>",
                "a" + "b".repeat(64))) {
            assertThatThrownBy(() -> presentation(namespace, "source.name", "schedule", "neutral"))
                    .as("非法 namespace: %s", namespace)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("i18n key 拒绝路径样式片段控制符与越界值")
    void i18nKeysRejectUnsafeOrOversizedValues() {
        for (String key : List.of(
                ".source",
                "source name",
                "source:name",
                "source/name",
                "source\nname",
                "<source>",
                "A" + "b".repeat(192))) {
            assertThatThrownBy(() -> presentation("example", key, "schedule", "neutral"))
                    .as("非法 i18n key: %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ScheduledSourcePresentation(
                    "example", "source.name", key, "schedule", "neutral"))
                    .as("非法 description key: %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("图标与颜色只接受小写有界展示 token")
    void iconAndColorRejectUnsafeOrOversizedValues() {
        for (String token : List.of(
                "Schedule",
                "schedule_icon",
                "schedule.icon",
                "schedule/icon",
                "color:red",
                "token value",
                "token\nvalue",
                "a" + "b".repeat(40))) {
            assertThatThrownBy(() -> presentation("example", "source.name", token, "neutral"))
                    .as("非法 icon token: %s", token)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> presentation("example", "source.name", "schedule", token))
                    .as("非法 color token: %s", token)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ScheduledSourcePresentation presentation(
            String namespace,
            String nameKey,
            String icon,
            String color) {
        return new ScheduledSourcePresentation(
                namespace, nameKey, "source.description", icon, color);
    }
}
