package top.sywyar.pixivdownload.plugin.api.service;

import java.util.List;

/**
 * 单个绑定的有界纯值证据，不包含载荷、异常对象、Bean 或 Class。
 * @param bindingId 当前运行时内的绑定序号
 * @param consumer 精确消费者发布身份
 * @param state 当前生命周期状态
 * @param code 最近状态原因的稳定码
 * @param dependencies 该原因对应的依赖与候选身份，后续换代不改写旧证据
 * @param truncated 依赖证据因展示预算被截断，不用于执行决策
 */
public record ServiceBindingDiagnostic(long bindingId, ServicePublication consumer, ServiceBinding.State state,
                                       String code, List<Dependency> dependencies, boolean truncated) {
    /**
     * 固定本次诊断的依赖快照。
     * @param bindingId 绑定序号
     * @param consumer 消费者发布
     * @param state 绑定状态
     * @param code 稳定原因码
     * @param dependencies 有界依赖证据
     * @param truncated 展示证据是否不完整
     */
    public ServiceBindingDiagnostic { dependencies = List.copyOf(dependencies); }

    /**
     * 不保留契约 Class 的依赖身份。
     * @param contractId 契约包身份
     * @param typeName 服务接口名
     * @param providers 精确候选，最多 128 项
     * @param truncated 名称或候选因展示预算被截断
     */
    public record Dependency(String contractId, String typeName, List<ServicePublication> providers, boolean truncated) {
        /**
         * 固定候选快照。
         * @param contractId 契约身份
         * @param typeName 类型名
         * @param providers 有界候选发布
         * @param truncated 名称或候选是否不完整
         */
        public Dependency { providers = List.copyOf(providers); }
    }
}
