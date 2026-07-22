package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.maintenance.MaintenanceTaskRegistry;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;

/** 将外置 child context 中的维护任务发布为宿主代理，并纳入统一 capability drain。 */
@Component
public class MaintenanceTaskCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {

    /** 宿主拥有的稳定任务元数据；只有执行行为进入可撤回 capability lease。 */
    private record PublishedMaintenanceTask(
            String name,
            MaintenanceTask executionProxy
        ) implements MaintenanceTask {

        @Override
        public void execute(MaintenanceContext context) throws Exception {
            executionProxy.execute(context);
        }
    }

    private record Prepared(
            ExternalCapabilityOwner owner,
            List<MaintenanceTaskRegistry.PreparedTask> tasks
    ) implements PreparedContribution {
        private Prepared {
            tasks = List.copyOf(tasks);
        }
    }

    private final MaintenanceTaskRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;

    @Autowired
    public MaintenanceTaskCapabilityAdapter(
            MaintenanceTaskRegistry registry,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this.registry = registry;
        this.invocationRegistry = invocationRegistry;
    }

    @Override
    public String capabilityName() {
        return MaintenanceTask.class.getName();
    }

    @Override
    public PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context) {
        List<MaintenanceTaskRegistry.PreparedTask> tasks = context
                .getBeansOfType(MaintenanceTask.class).entrySet().stream()
                .map(entry -> {
                    String beanName = entry.getKey();
                    MaintenanceTask target = entry.getValue();
                    int order = invocationRegistry.captureMetadata(
                            preparation, MaintenanceTask.class, "maintenance task order",
                            () -> MaintenanceTaskRegistry.resolveOrder(
                                    context.getBeanFactory(), beanName, target));
                    String name = invocationRegistry.captureMetadata(
                            preparation, MaintenanceTask.class, "maintenance task name", target::name);
                    if (name == null || name.isBlank()) {
                        throw new IllegalArgumentException("external maintenance task name must not be blank");
                    }
                    MaintenanceTask executionProxy = invocationRegistry.prepareProxy(
                            preparation, MaintenanceTask.class, target);
                    return new MaintenanceTaskRegistry.PreparedTask(
                            target instanceof PriorityOrdered,
                            order,
                            new PublishedMaintenanceTask(name, executionProxy));
                })
                .toList();
        return new Prepared(preparation.owner(), tasks);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(prepared.owner(), prepared.tasks());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner);
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared maintenance capability contribution");
    }
}
