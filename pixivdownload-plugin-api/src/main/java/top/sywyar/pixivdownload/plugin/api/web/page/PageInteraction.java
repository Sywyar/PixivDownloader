package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/** 实例内的一次状态与响应协议，与普通数据端口的 DAG 分离。 */
public record PageInteraction(String id, int version, String stateSchema, String responseSchema) {
    /** 表单 owner 声明的受控区域；多个参与者必须全部通过。 */
    public record Target(String id, String labelKey, String operation, String region,
            String contextSchema, List<PageInteraction> protocols, int maximumParticipants) {
        /**
         * 固定目标允许的交互协议。
         * @param id 页面内目标身份
         * @param labelKey 页面命名空间中的名称键
         * @param operation owner 命名空间中的操作身份
         * @param region 允许参与组件所在的受控区域
         * @param contextSchema 业务 owner 筛选后的上下文 schema
         * @param protocols 允许的协议与版本
         * @param maximumParticipants 同一目标最多参与组件数，全部必需
         */
        public Target { protocols = List.copyOf(protocols); }
    }

    /** 策略引用来自组件声明，页面数据不能自报 provider 或服务键。 */
    public record Participant(String policy, PageInteraction protocol) { }
}
