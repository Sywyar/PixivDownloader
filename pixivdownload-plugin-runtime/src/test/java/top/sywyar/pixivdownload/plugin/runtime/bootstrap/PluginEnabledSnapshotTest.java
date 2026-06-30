package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启用快照解析测试：覆盖 config.yaml {@code plugins.<featureId>.enabled} 的 true / false / 缺项 / 非法值处理，
 * 与缺项默认启用、不可变性、安全回退语义。
 */
@DisplayName("PluginEnabledSnapshot 启用快照解析（true/false/缺项/非法值）")
class PluginEnabledSnapshotTest {

    @Test
    @DisplayName("缺项 / 空段：全部默认启用、无禁用、无诊断")
    void emptyAndMissingDefaultsToEnabled() {
        assertThat(PluginEnabledSnapshot.empty().isEnabled("anything")).isTrue();
        assertThat(PluginEnabledSnapshot.empty().disabledFeatureIds()).isEmpty();
        assertThat(PluginEnabledSnapshot.of(null, "config.yaml").disabledFeatureIds()).isEmpty();
        assertThat(PluginEnabledSnapshot.of(Map.of(), "config.yaml").disabledFeatureIds()).isEmpty();
    }

    @Test
    @DisplayName("布尔值 true/false：false 进禁用集、true 不进")
    void booleanValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("gallery", Map.of("enabled", true));
        raw.put("novel", Map.of("enabled", false));
        raw.put("stats", Map.of("enabled", false));

        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");
        assertThat(snapshot.isEnabled("gallery")).isTrue();
        assertThat(snapshot.isEnabled("novel")).isFalse();
        assertThat(snapshot.isEnabled("stats")).isFalse();
        assertThat(snapshot.disabledFeatureIds()).containsExactlyInAnyOrder("novel", "stats");
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("缺项默认启用：未出现的 feature id 一律 true")
    void missingEntriesDefaultEnabled() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("novel", Map.of("enabled", false));
        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");
        // 未在配置出现的内置插件缺项默认启用
        assertThat(snapshot.isEnabled("gallery")).isTrue();
        assertThat(snapshot.isEnabled("download-workbench")).isTrue();
        assertThat(snapshot.isEnabled("unknown")).isTrue();
        assertThat(snapshot.isEnabled(null)).isTrue();
    }

    @Test
    @DisplayName("顶层 null 值视为缺项默认启用")
    void nullValueDefaultsEnabled() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("gallery", null);
        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");
        assertThat(snapshot.isEnabled("gallery")).isTrue();
        assertThat(snapshot.disabledFeatureIds()).isEmpty();
    }

    @Test
    @DisplayName("非法值（enabled 非布尔、字符串非 true/false、不支持的类型）：记诊断、按默认启用处理、不致命")
    void illegalValuesRecordDiagnosticsAndDefaultEnabled() {
        Map<String, Object> raw = new LinkedHashMap<>();
        // Map 的 enabled 是非布尔字符串
        raw.put("a", Map.of("enabled", "maybe"));
        // Map 的 enabled 是数字
        raw.put("b", Map.of("enabled", 1));
        // Map 无 enabled 键（视为缺项→启用，无诊断）
        raw.put("c", Map.of("description", "no enabled key"));
        // 顶层裸字符串非法值
        raw.put("d", "yes");
        // 顶层裸布尔（合法）
        raw.put("e", false);
        // 顶层不支持的类型
        raw.put("f", List.of(1, 2));

        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");
        // 非法值一律按默认启用，不进禁用集；仅 e=false 合法禁用
        assertThat(snapshot.isEnabled("a")).isTrue();
        assertThat(snapshot.isEnabled("b")).isTrue();
        assertThat(snapshot.isEnabled("c")).isTrue();
        assertThat(snapshot.isEnabled("d")).isTrue();
        assertThat(snapshot.isEnabled("e")).isFalse();
        assertThat(snapshot.isEnabled("f")).isTrue();
        assertThat(snapshot.disabledFeatureIds()).containsExactly("e");
        // a/b/d/f 四个非法值各记一条诊断
        assertThat(snapshot.hasDiagnostics()).isTrue();
        assertThat(snapshot.diagnostics()).hasSize(4);
        assertThat(snapshot.diagnostics()).allSatisfy(d -> assertThat(d).contains("config.yaml"));
    }

    @Test
    @DisplayName("合法字符串 true/false 同布尔语义")
    void stringTrueFalseAccepted() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("a", Map.of("enabled", "false"));
        raw.put("b", Map.of("enabled", "true"));
        raw.put("c", "FALSE");
        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");
        assertThat(snapshot.isEnabled("a")).isFalse();
        assertThat(snapshot.isEnabled("b")).isTrue();
        assertThat(snapshot.isEnabled("c")).isFalse();
        assertThat(snapshot.disabledFeatureIds()).containsExactlyInAnyOrder("a", "c");
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("ofDisabled 直接构造：保留传入禁用集与诊断")
    void ofDisabledDirect() {
        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.ofDisabled(
                List.of("novel", "stats"), List.of("a diagnostic"));
        assertThat(snapshot.isEnabled("novel")).isFalse();
        assertThat(snapshot.isEnabled("core")).isTrue();
        assertThat(snapshot.disabledFeatureIds()).containsExactlyInAnyOrder("novel", "stats");
        assertThat(snapshot.diagnostics()).containsExactly("a diagnostic");
    }

    @Test
    @DisplayName("不可变性：disabledFeatureIds / diagnostics 不可修改")
    void immutableCollections() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("novel", Map.of("enabled", false));
        PluginEnabledSnapshot snapshot = PluginEnabledSnapshot.of(raw, "config.yaml");

        assertThatThrownBy(() -> snapshot.disabledFeatureIds().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.diagnostics().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
