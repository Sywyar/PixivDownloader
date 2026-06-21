package top.sywyar.pixivdownload.plugin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件 API 契约版本常量与兼容策略契约测试")
class PluginApiVersionTest {

    @Test
    @DisplayName("VERSION 字符串必须与 MAJOR/MINOR/PATCH 数字常量一致")
    void versionStringMatchesNumericConstants() {
        assertThat(PluginApiVersion.VERSION)
                .isEqualTo(PluginApiVersion.MAJOR + "." + PluginApiVersion.MINOR + "." + PluginApiVersion.PATCH);
        assertThat(PluginApiVersion.current()).isEqualTo(PluginApiVersion.VERSION);
    }

    @Test
    @DisplayName("冻结的契约版本是稳定基线：MAJOR≥1，且各段非负")
    void frozenContractIsStableAndNonNegative() {
        assertThat(PluginApiVersion.MAJOR).isGreaterThanOrEqualTo(1);
        assertThat(PluginApiVersion.MINOR).isGreaterThanOrEqualTo(0);
        assertThat(PluginApiVersion.PATCH).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("兼容校验：MAJOR 一致且核心 MINOR≥插件所需 MINOR 即兼容（PATCH 不参与）")
    void compatibilityFollowsMajorMinorPolicy() {
        int major = PluginApiVersion.MAJOR;
        int minor = PluginApiVersion.MINOR;

        // 同 MAJOR、所需 MINOR 不高于核心 MINOR → 兼容（含相等与更低）
        assertThat(PluginApiVersion.isCompatibleWith(major, minor)).isTrue();
        assertThat(PluginApiVersion.isCompatibleWith(major, 0)).isTrue();

        // 同 MAJOR、所需 MINOR 高于核心 MINOR → 不兼容（插件需要核心尚未提供的新增契约）
        assertThat(PluginApiVersion.isCompatibleWith(major, minor + 1)).isFalse();

        // MAJOR 不一致 → 一律不兼容（破坏性变更）
        assertThat(PluginApiVersion.isCompatibleWith(major + 1, minor)).isFalse();
        assertThat(PluginApiVersion.isCompatibleWith(major - 1, minor)).isFalse();
    }

    @Test
    @DisplayName("PluginApiVersion 是不可实例化的无状态工具类（final + 私有构造器）")
    void isFinalUtilityClassWithPrivateConstructor() {
        assertThat(Modifier.isFinal(PluginApiVersion.class.getModifiers())).isTrue();
        Constructor<?>[] constructors = PluginApiVersion.class.getDeclaredConstructors();
        assertThat(constructors).allSatisfy(c ->
                assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
    }
}
