package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件生命周期策略 token 解析")
class PluginLifecyclePolicyTest {

    @Test
    @DisplayName("缺失或空白 token 为兼容旧包默认热重载")
    void missingTokenDefaultsToHotReload() {
        assertThat(PluginLifecyclePolicy.parse(null)).isEqualTo(PluginLifecyclePolicy.HOT_RELOAD);
        assertThat(PluginLifecyclePolicy.parse("  ")).isEqualTo(PluginLifecyclePolicy.HOT_RELOAD);
    }

    @Test
    @DisplayName("三个稳定 token 精确映射到对应策略")
    void parsesStableTokens() {
        assertThat(PluginLifecyclePolicy.parse("hot-reload"))
                .isEqualTo(PluginLifecyclePolicy.HOT_RELOAD);
        assertThat(PluginLifecyclePolicy.parse("backend-restart"))
                .isEqualTo(PluginLifecyclePolicy.BACKEND_RESTART);
        assertThat(PluginLifecyclePolicy.parse("process-restart"))
                .isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART);
    }

    @Test
    @DisplayName("未知或大小写错误的显式 token 被拒绝")
    void rejectsUnknownExplicitToken() {
        assertThatThrownBy(() -> PluginLifecyclePolicy.parse("restart"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported plugin lifecycle policy");
        assertThatThrownBy(() -> PluginLifecyclePolicy.parse("HOT-RELOAD"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
