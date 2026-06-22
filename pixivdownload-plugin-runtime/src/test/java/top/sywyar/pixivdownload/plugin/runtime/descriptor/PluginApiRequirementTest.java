package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件版本要求解析与兼容判定")
class PluginApiRequirementTest {

    @Test
    @DisplayName("空 / null 声明解析为未声明：兼容任何版本")
    void blankParsesToUnspecified() {
        for (String raw : new String[]{null, "", "   "}) {
            PluginApiRequirement requirement = PluginApiRequirement.parse(raw);
            assertThat(requirement.present()).isFalse();
            assertThat(requirement.valid()).isTrue();
            assertThat(requirement.isSatisfiedBy(0, 0)).isTrue();
            assertThat(requirement.isSatisfiedByCurrentApi()).isTrue();
            assertThat(requirement.display()).isEqualTo("(unspecified)");
        }
    }

    @Test
    @DisplayName("解析 MAJOR / MAJOR.MINOR / MAJOR.MINOR.PATCH：取 major.minor，PATCH 被忽略")
    void parsesVariousVersionForms() {
        assertThat(PluginApiRequirement.parse("2")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(2, 0);
        assertThat(PluginApiRequirement.parse("2.5")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(2, 5);
        assertThat(PluginApiRequirement.parse("2.5.9")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(2, 5);
    }

    @Test
    @DisplayName("容忍前导比较符 / v 前缀与范围首段")
    void tolerantParsing() {
        assertThat(PluginApiRequirement.parse(">=1.2")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(1, 2);
        assertThat(PluginApiRequirement.parse("v3.4.0")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(3, 4);
        assertThat(PluginApiRequirement.parse("1.0.0 & <2.0.0")).extracting(
                PluginApiRequirement::major, PluginApiRequirement::minor).containsExactly(1, 0);
    }

    @Test
    @DisplayName("无前导版本号的声明判为无效：恒不满足且回显原始串")
    void unparseableIsInvalid() {
        PluginApiRequirement requirement = PluginApiRequirement.parse("latest");
        assertThat(requirement.present()).isTrue();
        assertThat(requirement.valid()).isFalse();
        assertThat(requirement.isSatisfiedBy(1, 0)).isFalse();
        assertThat(requirement.display()).contains("latest");
    }

    @Test
    @DisplayName("收紧：任意文字前缀（abc1.2 / version1.0）不再被误读为版本，判为无效")
    void rejectsArbitraryTextPrefix() {
        PluginApiRequirement abc = PluginApiRequirement.parse("abc1.2");
        assertThat(abc.present()).isTrue();
        assertThat(abc.valid()).isFalse();
        assertThat(abc.isSatisfiedBy(1, 2)).isFalse();
        assertThat(abc.display()).contains("abc1.2");

        // 单个合法 v 前缀后必须紧跟数字；"version1.0" 的 v 之后是字母 → 无效
        assertThat(PluginApiRequirement.parse("version1.0").valid()).isFalse();
        assertThat(PluginApiRequirement.parse("ext-1.0").valid()).isFalse();
    }

    @Test
    @DisplayName("星号 * 不限版本标记解析为未声明：兼容任何版本")
    void asteriskParsesToUnspecified() {
        PluginApiRequirement any = PluginApiRequirement.parse("*");
        assertThat(any.present()).isFalse();
        assertThat(any.valid()).isTrue();
        assertThat(any.isSatisfiedByCurrentApi()).isTrue();
        assertThat(any.isSatisfiedBy(7, 3)).isTrue();
    }

    @Test
    @DisplayName("兼容判定委托 PluginApiVersion 同一规则：同 MAJOR 且提供方 MINOR 不低于所需")
    void satisfactionDelegatesToVersionRule() {
        PluginApiRequirement needs1_2 = PluginApiRequirement.of(1, 2);
        assertThat(needs1_2.isSatisfiedBy(1, 2)).isTrue();
        assertThat(needs1_2.isSatisfiedBy(1, 5)).isTrue();
        assertThat(needs1_2.isSatisfiedBy(1, 1)).isFalse();
        assertThat(needs1_2.isSatisfiedBy(2, 2)).isFalse();
        // 与底层规则一致
        assertThat(needs1_2.isSatisfiedBy(1, 5))
                .isEqualTo(PluginApiVersion.isCompatible(1, 5, 1, 2));
    }

    @Test
    @DisplayName("isSatisfiedByCurrentApi 以核心版本为提供方")
    void currentApiSatisfaction() {
        PluginApiRequirement matchesCore =
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR);
        assertThat(matchesCore.isSatisfiedByCurrentApi()).isTrue();
        // 所需 MINOR 高于核心 → 不满足
        assertThat(PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR + 1)
                .isSatisfiedByCurrentApi()).isFalse();
        // 所需 MAJOR 高于核心 → 不满足
        assertThat(PluginApiRequirement.of(PluginApiVersion.MAJOR + 1, 0)
                .isSatisfiedByCurrentApi()).isFalse();
    }
}
