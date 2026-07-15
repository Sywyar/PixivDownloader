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
    @DisplayName("来源默认网络路由契约发布为向后兼容的 1.3 版本")
    void frozenContractIsStableAndNonNegative() {
        assertThat(PluginApiVersion.MAJOR).isEqualTo(1);
        assertThat(PluginApiVersion.MINOR).isEqualTo(3);
        assertThat(PluginApiVersion.PATCH).isEqualTo(0);
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
    @DisplayName("通用兼容判定 isCompatible：同 MAJOR 且提供方 MINOR≥所需即兼容，独立于核心常量")
    void generalCompatibilityRuleIsReusable() {
        // 任意提供方 2.3：满足所需 2.0 / 2.3（同 MAJOR、MINOR 不更高），不满足 2.4（MINOR 更高）/ 1.x / 3.x（MAJOR 不一致）
        assertThat(PluginApiVersion.isCompatible(2, 3, 2, 0)).isTrue();
        assertThat(PluginApiVersion.isCompatible(2, 3, 2, 3)).isTrue();
        assertThat(PluginApiVersion.isCompatible(2, 3, 2, 4)).isFalse();
        assertThat(PluginApiVersion.isCompatible(2, 3, 1, 3)).isFalse();
        assertThat(PluginApiVersion.isCompatible(2, 3, 3, 0)).isFalse();
        // isCompatibleWith 是「核心版本为提供方」的特例，必须与通用规则一致
        int major = PluginApiVersion.MAJOR;
        int minor = PluginApiVersion.MINOR;
        assertThat(PluginApiVersion.isCompatibleWith(major, minor))
                .isEqualTo(PluginApiVersion.isCompatible(major, minor, major, minor));
        assertThat(PluginApiVersion.isCompatibleWith(major, minor + 1))
                .isEqualTo(PluginApiVersion.isCompatible(major, minor, major, minor + 1));
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
