package top.sywyar.pixivdownload.plugin.api.service;

import java.util.Optional;

/**
 * 宿主注入的消费方访问器，只允许使用显式导入的服务。
 * 构造器和 PostConstruct 可以保存访问器，但 publication 激活前调用会失败。
 * 缺少提供者返回空；多个活动提供者明确报冲突。代理绑定双方精确 publication，
 * 任一方撤回后拒绝新调用，不转接新提供者、不重放请求；在途调用实际退出前保持租约。
 * 此入口不代替队列、流、异步任务等专用生命周期契约。
 *
 * <p>接口不得声明类型参数、泛型方法或 throws。值支持基本类型及包装类、String、UUID、Instant、Duration、
 * 无实例状态的契约 enum、数组、显式泛型 List/Set/Map/Optional 和契约 public record。
 * record 只接受按声明顺序保存参数的 canonical 构造器；复制读取实际字段，不执行自定义 accessor。
 * Set 元素及 Map 键限上述不可变标量。拒绝 Object、通配符、未绑定类型变量、回调、异步值、资源句柄和循环。
 * 单次调用的参数与结果共用 1 MiB 估算复制预算、16,384 个值节点和 32 层深度；同一 registry 至多 64 个在途调用。
 * 这些是内存保护预算，不是序列化字节数或执行超时。调用取消或等待超时不表示提供者已退出。
 * 构造器、PostConstruct 和插件 start 回调不得假设自身服务已发布；首个业务请求应再解析所需服务。
 */
public interface PluginServices {
    /**
     * 登记一组服务依赖；可在初始化时登记，但激活只在消费者及全部唯一提供者发布后执行。
     * 回调负责通过本轮 scope 登记订阅、辅助回调与临时注册；不得提交需要自动重放的业务命令。
     * 失效先关闭本轮准入，实际在途调用退出且资源清理成功后才建立下一轮。
     * 每个消费者最多登记 128 个意图，每个意图最多包含已声明的 128 项导入。
     *
     * @param dependencies 非空且无重复的已声明导入
     * @param activate 同步激活回调；异常导致本轮资源回滚，不自动重试相同选择
     * @return 可查询、重试和永久撤销的意图
     * @throws ServiceAccessException 未声明依赖、消费者已撤回、容量不足或实现不支持
     */
    default ServiceBinding bind(java.util.List<ServiceKey<?>> dependencies,
            java.util.function.Consumer<ServiceBindingScope> activate) {
        throw new ServiceAccessException(ServiceAccessException.Reason.UNSUPPORTED);
    }

    /**
     * 解析当前唯一提供者。返回代理只用于同步纯值调用，参数和结果均被校验并复制。
     *
     * @param key 已声明导入的契约
     * @param <T> 服务类型
     * @return 缺席时为空，否则为绑定当前双方的代理
     * @throws ServiceAccessException 未激活、未声明或提供者冲突
     */
    <T> Optional<T> find(ServiceKey<T> key);

    /**
     * 解析当前服务；缺席明确失败，不伪造成功。
     *
     * @param key 已声明导入的契约
     * @param <T> 服务类型
     * @return 当前唯一提供者的代理
     * @throws ServiceAccessException 无法取得服务
     */
    default <T> T require(ServiceKey<T> key) {
        return find(key).orElseThrow(() -> new ServiceAccessException(ServiceAccessException.Reason.UNAVAILABLE));
    }
}
