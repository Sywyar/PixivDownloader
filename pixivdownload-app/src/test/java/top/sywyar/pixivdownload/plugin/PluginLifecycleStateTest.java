package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;

/**
 * 外置插件运行期生命周期状态机测试：合法流转、非法流转的清晰诊断、quiesce 观测、强制收尾与移除。
 */
@DisplayName("外置插件运行期生命周期状态机")
class PluginLifecycleStateTest {

    private final PluginLifecycleState state = new PluginLifecycleState();

    @Test
    @DisplayName("完整热启停流转：LOADED→STARTED→QUIESCED→STOPPED→UNLOADED→LOADED 全合法")
    void fullLifecycleTransitionsAreLegal() {
        state.initialize("p", PluginRuntimePhase.LOADED);
        state.transition("p", PluginRuntimePhase.STARTED);
        state.transition("p", PluginRuntimePhase.QUIESCED);
        state.transition("p", PluginRuntimePhase.STOPPED);
        state.transition("p", PluginRuntimePhase.UNLOADED);
        state.transition("p", PluginRuntimePhase.LOADED);
        assertThat(state.phase("p")).contains(PluginRuntimePhase.LOADED);
    }

    @Test
    @DisplayName("start→stop→start 可重复：STARTED⇄STOPPED 往返合法")
    void startStopStartIsRepeatable() {
        state.initialize("p", PluginRuntimePhase.STARTED);
        state.transition("p", PluginRuntimePhase.STOPPED);
        state.transition("p", PluginRuntimePhase.STARTED);
        state.transition("p", PluginRuntimePhase.STOPPED);
        state.transition("p", PluginRuntimePhase.STARTED);
        assertThat(state.phase("p")).contains(PluginRuntimePhase.STARTED);
    }

    @Test
    @DisplayName("非法流转抛 PluginLifecycleException 并带「当前态 -> 目标态」诊断")
    void illegalTransitionFailsWithDiagnostic() {
        state.initialize("p", PluginRuntimePhase.QUIESCED);
        assertThatThrownBy(() -> state.transition("p", PluginRuntimePhase.STARTED))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("QUIESCED -> STARTED");
    }

    @Test
    @DisplayName("LOADED 不能直接 quiesce：非法流转被拒绝")
    void loadedCannotQuiesce() {
        state.initialize("p", PluginRuntimePhase.LOADED);
        assertThatThrownBy(() -> state.transition("p", PluginRuntimePhase.QUIESCED))
                .isInstanceOf(PluginLifecycleException.class);
    }

    @Test
    @DisplayName("对从未接入的插件做流转：清晰报错（无生命周期状态）")
    void transitionUnknownPluginFails() {
        assertThatThrownBy(() -> state.transition("ghost", PluginRuntimePhase.STARTED))
                .isInstanceOf(PluginLifecycleException.class)
                .hasMessageContaining("no lifecycle state");
    }

    @Test
    @DisplayName("set 强制收尾：不校验流转，可从任意态落到 STOPPED")
    void setForcesPhaseWithoutValidation() {
        state.initialize("p", PluginRuntimePhase.QUIESCED);
        state.set("p", PluginRuntimePhase.STOPPED);
        assertThat(state.phase("p")).contains(PluginRuntimePhase.STOPPED);
    }

    @Test
    @DisplayName("quiesce 观测：isQuiesced / acceptsNewRequests / quiescedPluginIds 一致")
    void quiesceObservability() {
        state.initialize("a", PluginRuntimePhase.STARTED);
        state.initialize("b", PluginRuntimePhase.STARTED);
        assertThat(state.acceptsNewRequests("a")).isTrue();
        assertThat(state.isQuiesced("a")).isFalse();
        assertThat(state.quiescedPluginIds()).isEmpty();

        state.transition("a", PluginRuntimePhase.QUIESCED);
        assertThat(state.acceptsNewRequests("a")).isFalse();
        assertThat(state.isQuiesced("a")).isTrue();
        assertThat(state.quiescedPluginIds()).containsExactly("a");
        // 未知插件视为不接收 / 非 quiesce
        assertThat(state.acceptsNewRequests("ghost")).isFalse();
        assertThat(state.isQuiesced("ghost")).isFalse();
    }

    @Test
    @DisplayName("remove 清理观测态：移除后阶段查询为空")
    void removeClearsState() {
        state.initialize("p", PluginRuntimePhase.STARTED);
        state.remove("p");
        assertThat(state.phase("p")).isEmpty();
    }

    @Test
    @DisplayName("枚举语义：仅 STARTED 接收新请求、仅 QUIESCED 为静默态")
    void phaseSemantics() {
        assertThat(PluginRuntimePhase.STARTED.acceptsNewRequests()).isTrue();
        for (PluginRuntimePhase phase : PluginRuntimePhase.values()) {
            if (phase != PluginRuntimePhase.STARTED) {
                assertThat(phase.acceptsNewRequests()).as("%s accepts", phase).isFalse();
            }
            assertThat(phase.isQuiesced()).isEqualTo(phase == PluginRuntimePhase.QUIESCED);
        }
    }
}
