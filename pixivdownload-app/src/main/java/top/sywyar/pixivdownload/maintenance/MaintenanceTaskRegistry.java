package top.sywyar.pixivdownload.maintenance;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.DecoratingProxy;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 宿主维护任务注册中心。
 *
 * <p>根上下文注入的任务是核心任务，始终保留；外置插件任务只由宿主已知的精确
 * {@link ExternalCapabilityOwner} 发布和撤回。读侧取得单个不可变快照，维护窗口不会因并发
 * reload 读到半份任务清单。
 */
@Component
public class MaintenanceTaskRegistry {

    /** 外置 adapter 在发布前已物化的排序值与无目标代理。 */
    public record PreparedTask(boolean priorityOrdered, int order, MaintenanceTask task) {
        public PreparedTask(int order, MaintenanceTask task) {
            this(task instanceof PriorityOrdered, order, task);
        }

        public PreparedTask {
            Objects.requireNonNull(task, "maintenance task proxy");
        }
    }

    private final Object lock = new Object();
    private final List<PreparedTask> coreTasks;
    private final Map<ExternalCapabilityOwner, List<PreparedTask>> externalTasks = new LinkedHashMap<>();
    private volatile List<MaintenanceTask> snapshot;

    @Autowired
    public MaintenanceTaskRegistry(
            Map<String, MaintenanceTask> coreTasks,
            ConfigurableListableBeanFactory beanFactory) {
        List<PreparedTask> preparedCore = new ArrayList<>();
        if (coreTasks != null) {
            coreTasks.forEach((beanName, task) -> {
                if (task != null) {
                    preparedCore.add(new PreparedTask(resolveOrder(beanFactory, beanName, task), task));
                }
            });
        }
        this.coreTasks = List.copyOf(preparedCore);
        this.snapshot = rebuildSnapshot();
    }

    /** Spring 上下文外构造：只解析实例自身的 {@link Ordered} / 类型级顺序。 */
    public MaintenanceTaskRegistry(List<MaintenanceTask> coreTasks) {
        List<PreparedTask> preparedCore = new ArrayList<>();
        if (coreTasks != null) {
            for (MaintenanceTask task : coreTasks) {
                if (task != null) {
                    preparedCore.add(new PreparedTask(orderOf(task), task));
                }
            }
        }
        this.coreTasks = List.copyOf(preparedCore);
        this.snapshot = rebuildSnapshot();
    }

    /** 发布一个精确外置代际的任务代理；同插件的旧 publication 未撤回时拒绝覆盖。 */
    public void registerPrepared(ExternalCapabilityOwner owner, List<PreparedTask> tasks) {
        Objects.requireNonNull(owner, "external maintenance owner");
        List<PreparedTask> copy = tasks == null ? List.of() : List.copyOf(tasks);
        synchronized (lock) {
            externalTasks.keySet().stream()
                    .filter(existing -> !existing.equals(owner))
                    .filter(existing -> existing.pluginId().equals(owner.pluginId()))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new IllegalStateException("external maintenance owner is still published: "
                                + existing);
                    });
            if (copy.isEmpty()) {
                externalTasks.remove(owner);
            } else {
                externalTasks.put(owner, copy);
            }
            snapshot = rebuildSnapshot();
        }
    }

    /** 只撤回完全匹配的 publication；过期 owner 不会删除 replacement。 */
    public void unregisterPrepared(ExternalCapabilityOwner owner) {
        if (owner == null) {
            return;
        }
        synchronized (lock) {
            if (externalTasks.remove(owner) != null) {
                snapshot = rebuildSnapshot();
            }
        }
    }

    /** 当前核心任务与活动外置任务的有序不可变快照。 */
    public List<MaintenanceTask> tasks() {
        return snapshot;
    }

    private List<MaintenanceTask> rebuildSnapshot() {
        List<PreparedTask> merged = new ArrayList<>(coreTasks);
        externalTasks.values().forEach(merged::addAll);
        merged.sort(Comparator
                .comparingInt((PreparedTask task) -> task.priorityOrdered() ? 0 : 1)
                .thenComparingInt(PreparedTask::order));
        return merged.stream().map(PreparedTask::task).toList();
    }

    /**
     * 按 Spring 集合注入的 factory-aware 口径解析 Bean 顺序。
     *
     * <p>BeanDefinition order attribute、{@code @Bean} 工厂方法与解析后的目标类型优先，
     * 找不到定义时再回退实例 {@link Ordered} / 类型注解。这样根上下文与外置 child context
     * 发布到同一注册中心后仍可比较真实的 Spring {@code @Order} 值。
     */
    public static int resolveOrder(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            MaintenanceTask task) {
        Objects.requireNonNull(beanFactory, "maintenance task bean factory");
        Objects.requireNonNull(beanName, "maintenance task bean name");
        Objects.requireNonNull(task, "maintenance task");

        Integer definitionOrder = orderFromBeanDefinition(beanFactory, beanName, task);
        return definitionOrder != null ? definitionOrder : orderOf(task);
    }

    private static Integer orderFromBeanDefinition(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            MaintenanceTask task) {
        try {
            BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanName);
            Object orderAttribute = beanDefinition.getAttribute(AbstractBeanDefinition.ORDER_ATTRIBUTE);
            if (orderAttribute != null) {
                if (orderAttribute instanceof Integer order) {
                    return order;
                }
                throw new IllegalStateException("invalid maintenance task order attribute type for bean '"
                        + beanName + "': " + orderAttribute.getClass().getName());
            }
            if (beanDefinition instanceof RootBeanDefinition rootBeanDefinition) {
                Method factoryMethod = rootBeanDefinition.getResolvedFactoryMethod();
                Integer factoryOrder = factoryMethod == null ? null : OrderUtils.getOrder(factoryMethod);
                if (factoryOrder != null) {
                    return factoryOrder;
                }
                Class<?> targetType = rootBeanDefinition.getTargetType();
                if (targetType != null && targetType != task.getClass()) {
                    Integer targetOrder = OrderUtils.getOrder(targetType);
                    if (targetOrder != null) {
                        return targetOrder;
                    }
                }
            }
            return null;
        } catch (NoSuchBeanDefinitionException ignored) {
            return null;
        }
    }

    private static int orderOf(MaintenanceTask task) {
        if (task instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        Integer order = OrderUtils.getOrder(ClassUtils.getUserClass(task));
        if (order == null && task instanceof DecoratingProxy decoratingProxy) {
            order = OrderUtils.getOrder(decoratingProxy.getDecoratedClass());
        }
        return order != null ? order : Ordered.LOWEST_PRECEDENCE;
    }
}
