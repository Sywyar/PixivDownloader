package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.context.ApplicationContext;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.quiesce.PluginRuntimeTaskQuiescer;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

/** Package bridge for deterministic fatal handoff probes in lifecycle integration tests. */
public final class PluginLifecycleServiceTestAccess {

    private PluginLifecycleServiceTestAccess() {
    }

    public static PluginLifecycleService withCapabilityHandoffProbes(
            ApplicationContext parent,
            PluginRuntimeManager pluginRuntimeManager,
            PluginApplicationContextFactory contextFactory,
            PluginControllerRegistrar controllerRegistrar,
            PluginWebContributionRegistrar webContributionRegistrar,
            PluginScheduleContributionRegistrar scheduleContributionRegistrar,
            PluginRuntimeTaskQuiescer runtimeTaskQuiescer,
            PluginCapabilityContributionRegistrar capabilityContributionRegistrar,
            PluginRegistry pluginRegistry,
            PluginLifecycleState lifecycleState,
            Runnable afterPublishReturnProbe,
            Runnable afterWithdrawReturnProbe,
            Runnable afterRetireReturnProbe,
            Runnable afterAcknowledgeReturnProbe,
            Runnable afterAcknowledgeFlagProbe) {
        return new PluginLifecycleService(
                parent,
                pluginRuntimeManager,
                contextFactory,
                controllerRegistrar,
                webContributionRegistrar,
                scheduleContributionRegistrar,
                runtimeTaskQuiescer,
                capabilityContributionRegistrar,
                pluginRegistry,
                lifecycleState,
                afterPublishReturnProbe,
                afterWithdrawReturnProbe,
                afterRetireReturnProbe,
                afterAcknowledgeReturnProbe,
                afterAcknowledgeFlagProbe);
    }
}
