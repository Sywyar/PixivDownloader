package top.sywyar.pixivdownload.plugin.api.operation;

import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

/** 同一可信目录的表单、操作和接口查询；发现不授予调用或跨 owner 绑定权限。 */
public interface OperationTargets {
    /** @param reference 明确目标身份；不从 DOM、按钮文字或客户端 URL 推导
     * @return 调用身份可见的候选及状态，未声明和歧义不能任选一个候选执行
     */
    Resolution resolveTargets(Reference reference);

    /**
     * @param pageId 页面身份
     * @param targetId 页面声明的动作身份
     * @return 同一解析器的接口集合
     */
    default Resolution getFormEndpoints(String pageId, String targetId) {
        return resolveTargets(new Reference(Kind.FORM_ACTION, pageId, targetId));
    }

    /** 按正式关系显式选择同一表单的提交动作。
     * @param pageId 页面身份
     * @param formId 保存的原生表单身份
     * @param actionId 提交按钮身份；空值查询唯一动作或返回歧义
     * @return 同一解析器的正式关系与候选
     */
    default Resolution getFormEndpoints(String pageId, String formId, String actionId) {
        return resolveTargets(new Reference(Kind.FORM_ACTION, pageId, formId, actionId));
    }

    /** 表单动作、操作或单接口，表单动作必须带 targetId。 */
    enum Kind {
        /** 页面与声明动作。 */ FORM_ACTION,
        /** 操作及其全部接口。 */ OPERATION,
        /** 单个真实 handler。 */ ENDPOINT
    }
    /** 明确拒绝状态，不能把目录不可用当成无保护。 */
    enum Status {
        /** 可信声明可用。 */ AVAILABLE,
        /** 当前目录未声明该身份。 */ UNDECLARED,
        /** 目录或所属能力不可用。 */ UNAVAILABLE,
        /** 需要显式选择动作或消除冲突。 */ AMBIGUOUS,
        /** 声明合同不兼容。 */ INCOMPATIBLE,
        /** 查询所依赖的代际已失效。 */ STALE,
        /** 调用身份不可发现该目标。 */ FORBIDDEN
    }
    /** id 为 pageId、operationId 或 endpointId，targetId 只用于 FORM_ACTION。 */
    record Reference(Kind kind, String id, String targetId, String actionId) {
        /** 按已有 targetId 查询。
         * @param kind 目标类型
         * @param id 页面、接口组或接口身份
         * @param targetId 页面声明或配置的动作身份
         */
        public Reference(Kind kind, String id, String targetId) { this(kind, id, targetId, null); }
    }
    /** 真实 handler 可以拥有多个路由映射，不默取第一个 URL。 */
    record Route(HttpMethod method, String template) { }
    /** 只含宿主物化的稳定纯值；publication 撤回后不可复用。 */
    record Endpoint(String id, String operation, String owner, String publication, String revision,
            List<Route> routes, Set<OperationHook.Phase> phases, String inputSchema, String resultSchema,
            List<String> parameters, List<String> headers, List<String> consumes, List<String> produces,
            String handler, String shape) {
        /**
         * 固定当前目录投影。
         * @param id 接口身份
         * @param operation 操作身份
         * @param owner 宿主确认的 owner
         * @param publication 精确生命周期身份
         * @param revision 合同修订
         * @param routes 全部 HTTP 映射
         * @param phases 允许阶段
         * @param inputSchema 输入投影合同
         * @param resultSchema 成功结果投影合同
         * @param parameters MVC 参数匹配条件
         * @param headers MVC 请求头匹配条件
         * @param consumes MVC 输入内容类型条件
         * @param produces MVC 输出内容类型条件
         * @param handler 有界诊断入口身份
         * @param shape 同步、异步或流式入口形态
         */
        public Endpoint {
            routes = List.copyOf(routes); phases = Set.copyOf(phases);
            parameters = List.copyOf(parameters); headers = List.copyOf(headers);
            consumes = List.copyOf(consumes); produces = List.copyOf(produces);
        }
    }
    /** 候选操作及接口使用同一目录修订，不承诺未声明的旧 handler 已自动受保护。 */
    record Resolution(Status status, String revision, List<String> operations, List<Endpoint> endpoints) {
        /**
         * 固定候选集合。
         * @param status 查询结论
         * @param revision 当前目录身份
         * @param operations 候选操作
         * @param endpoints 候选接口
         */
        public Resolution { operations = List.copyOf(operations); endpoints = List.copyOf(endpoints); }
    }
}
