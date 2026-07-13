package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 必选插件策略的配置开关：{@code recovery-sentinel} 只有在显式开关
 * {@code pixivdownload.recovery-sentinel.required=true} 打开时才被核心策略追加为必选项；下载工作台
 * {@code download-workbench} 与开关无关、恒为必选。验证 {@code PluginRuntimeConfiguration.requiredPluginPolicy}
 * 据 {@link org.springframework.core.env.Environment} 解析该开关。
 */
@DisplayName("必选插件策略配置开关：pixivdownload.recovery-sentinel.required 控制 recovery-sentinel 是否必选")
class RecoverySentinelRequiredPolicyTest {

    private final PluginRuntimeConfiguration configuration = new PluginRuntimeConfiguration();

    @Test
    @DisplayName("默认（开关未设置）：recovery-sentinel 不是必选，download-workbench 仍必选")
    void recoverySentinelNotRequiredByDefault() {
        RequiredPluginPolicy policy = configuration.requiredPluginPolicy(new MockEnvironment());

        assertThat(policy.isRequired("recovery-sentinel")).isFalse();
        assertThat(policy.isRequired("download-workbench")).isTrue();
        RequiredPlugin downloadWorkbench = policy.requirement("download-workbench").orElseThrow();
        assertThat(downloadWorkbench.compatibleVersion().major()).isEqualTo(1);
        assertThat(downloadWorkbench.compatibleVersion().minor()).isZero();
    }

    @Test
    @DisplayName("开关显式 false：recovery-sentinel 不是必选")
    void recoverySentinelNotRequiredWhenFlagFalse() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("pixivdownload.recovery-sentinel.required", "false");

        RequiredPluginPolicy policy = configuration.requiredPluginPolicy(env);

        assertThat(policy.isRequired("recovery-sentinel")).isFalse();
        assertThat(policy.isRequired("download-workbench")).isTrue();
    }

    @Test
    @DisplayName("开关 true：recovery-sentinel 追加为必选（不允许禁用、不约束插件自身版本）")
    void recoverySentinelRequiredWhenFlagTrue() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("pixivdownload.recovery-sentinel.required", "true");

        RequiredPluginPolicy policy = configuration.requiredPluginPolicy(env);

        assertThat(policy.isRequired("recovery-sentinel")).isTrue();
        assertThat(policy.isRequired("download-workbench")).isTrue();

        RequiredPlugin sentinel = policy.requirement("recovery-sentinel").orElseThrow();
        assertThat(sentinel.allowDisable()).isFalse();
        // 不约束插件自身版本：兼容版本为「未声明」，故任意版本的 recovery-sentinel 都满足存在性要求。
        assertThat(sentinel.compatibleVersion().present()).isFalse();
    }
}
