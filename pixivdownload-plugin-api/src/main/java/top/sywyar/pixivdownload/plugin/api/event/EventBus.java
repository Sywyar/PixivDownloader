package top.sywyar.pixivdownload.plugin.api.event;

import java.util.function.Consumer;

/**
 * 插件间轻量事件总线。插件代码只面向本接口发布/订阅事件，
 * 不直接使用 Spring 的 {@code ApplicationEventPublisher}（Spring 事件
 * 不跨父子 context 传播）；事件类一律放在 {@code plugin.api}。
 * <p>
 * 订阅按 {@code subscriberPluginId} 归属登记，插件停止时由核心统一清退
 * 其全部订阅，这是运行期卸载插件不泄漏监听器的前提。
 */
public interface EventBus {

    /** 发布事件，同步分发给所有匹配类型的订阅者。 */
    void publish(Object event);

    /**
     * 订阅指定类型（含其子类型）的事件。
     *
     * @param subscriberPluginId 订阅方插件 id
     * @param eventType          事件类型
     * @param listener           事件回调
     * @return 订阅句柄，关闭即退订
     */
    <E> EventSubscription subscribe(String subscriberPluginId, Class<E> eventType, Consumer<E> listener);
}
