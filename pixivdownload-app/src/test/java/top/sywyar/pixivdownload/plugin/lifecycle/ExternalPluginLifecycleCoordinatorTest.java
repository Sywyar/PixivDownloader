package top.sywyar.pixivdownload.plugin.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("外置插件运行期生命周期协调")
class ExternalPluginLifecycleCoordinatorTest {

    @Mock
    PluginRuntimeManager runtimeManager;
    @Mock
    PluginLifecycleService lifecycleService;
    @Mock
    ExternalPluginInstaller installer;
    @Mock
    RecoveryModeService recoveryModeService;
    @Mock
    PluginDependencyResolver dependencyResolver;

    @Test
    @DisplayName("停止后的插件按可激活描述符校验并依次恢复框架与服务足迹")
    void startRestoresStoppedPluginFromActivationDescriptor() {
        String pluginId = "dev-plugin";
        PluginDescriptor descriptor = new PluginDescriptor(pluginId, pluginId, "1.0.0",
                PluginApiRequirement.unspecified(), List.of(), "example.Plugin", null,
                "plugin.label", null, null, null, PluginKind.FEATURE);
        when(dependencyResolver.activationDescriptor(pluginId)).thenReturn(Optional.of(descriptor));
        when(dependencyResolver.activationProblems(descriptor)).thenReturn(List.of());
        when(lifecycleService.phase(pluginId)).thenReturn(Optional.of(PluginRuntimePhase.STARTED));

        new ExternalPluginLifecycleCoordinator(runtimeManager, lifecycleService, installer,
                recoveryModeService, dependencyResolver).start(pluginId);

        InOrder order = inOrder(dependencyResolver, runtimeManager, lifecycleService);
        order.verify(dependencyResolver).activationDescriptor(pluginId);
        order.verify(dependencyResolver).activationProblems(descriptor);
        order.verify(runtimeManager).startPlugin(pluginId);
        order.verify(lifecycleService).start(pluginId);
        verify(recoveryModeService).refresh();
    }
}
