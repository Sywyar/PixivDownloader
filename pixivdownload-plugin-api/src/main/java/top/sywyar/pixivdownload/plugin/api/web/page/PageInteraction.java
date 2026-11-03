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

    /** 管理员发布的表单动作关系；id 复用 Target 身份，operation 与 endpoint 二选一。 */
    public record Action(String id, String formId, String actionId, String region, String operation, String endpoint) {
        /** 校验有界身份；接口存在性和绑定权限由宿主发布时校验。
         * @param id 页面内 target 身份
         * @param formId 原生表单身份
         * @param actionId 提交按钮身份，默认动作可为空
         * @param region 参与组件的受控区域
         * @param operation 接口组，与 endpoint 二选一
         * @param endpoint 单接口身份
         */
        public Action {
            for (String value : List.of(id, formId, region))
                if (!value.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("PAGE_ACTION_INVALID");
            if (actionId == null || !actionId.matches("[A-Za-z0-9._-]{0,128}")
                    || operation == null || endpoint == null || operation.isEmpty() == endpoint.isEmpty()
                    || !(operation.isEmpty() ? endpoint : operation).matches("[a-z0-9-]+:[A-Za-z0-9:._-]{1,128}"))
                throw new IllegalArgumentException("PAGE_ACTION_INVALID");
        }
    }
}
