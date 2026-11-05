package top.sywyar.pixivdownload.plugin.api.operation;

import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;

/**
 * 插件贡献的同步调用回调。宿主绑定可信 owner 和精确 publication，并持有真实在途租约。
 * 必需前置回调缺席、故障或超时拒绝业务；后置回调仅观察，不改变已完成业务，也不重放命令。
 * HTTP 数据由宿主按正式绑定的字段白名单提取；本地调用使用 owner 投影。
 * 仅传递有界纯值，不包含 HTTP 对象、业务动作或异常对象。
 */
public interface OperationHook {
    /** 业务 owner 的显式受管调用权限；此键不允许直接发现全局回调。 */
    ServiceKey<OperationHook> IMPORT = new ServiceKey<>("operation-hooks", OperationHook.class);

    /** {@return 稳定 Hook 身份，实际提供者身份由宿主盖章} */
    String id();
    /** {@return 纯值合同；合同变化必须更新 revision 并重新批准绑定} */
    Definition definition();
    /** {@return 验证凭据等运行状态的代际，变化使旧挑战失效} */
    String generation();
    /**
     * 同步处理当前阶段的受控调用。
     * @param invocation 当前阶段的受控输入；失败不携带 Throwable 或原始业务响应
     * @return 前置决策；后置返回值不能改变业务结果
     */
    Decision invoke(Invocation invocation);

    /** 宿主安全检查之后的前置阶段，以及请求或本地动作完成后的观察阶段。 */
    enum Phase {
        /** 宿主准入之后、业务副作用之前。 */ BEFORE_INVOKE,
        /** HTTP 请求实际完成，或本地同步动作返回、抛错、被阻断之后。 */ AFTER_COMPLETION
    }
    /** HTTP 完成事实；SUCCEEDED 不推断后台任务或响应正文中的领域业务结果。 */
    enum Outcome {
        /** 前置阶段尚无业务结果。 */ NONE,
        /** 业务未执行。 */ BLOCKED,
        /** HTTP 状态小于 400，或本地同步动作返回；不推断响应正文中的业务结果。 */ SUCCEEDED,
        /** HTTP 错误状态、异常或本地动作失败，不表示副作用已回滚。 */ FAILED,
        /** 请求超时；不表示仍在运行的 Java 工作已退出。 */ TIMED_OUT,
        /** 客户端断开或请求取消。 */ CANCELLED
    }
    /** 后置筛选；失败包含业务失败，不把前置阻断伪装成业务已执行。 */
    enum Completion {
        /** 观察所有进入通用调用流程的完成状态。 */ ALL,
        /** 仅观察成功。 */ SUCCEEDED,
        /** 仅观察业务失败。 */ FAILED,
        /** 仅观察业务未执行的阻断。 */ BLOCKED,
        /** 仅观察请求超时。 */ TIMED_OUT,
        /** 仅观察请求取消。 */ CANCELLED
    }
    /** REJECT 优先于 CHALLENGE；原 HTTP 请求不会等待用户交互。 */
    enum Verdict {
        /** 继续检查其它必要 gate，不代表授权后续请求。 */ CONTINUE,
        /** 拒绝本次业务。 */ REJECT,
        /** 返回受控挑战，结束当前 HTTP 请求。 */ CHALLENGE
    }

    /**
     * 空 inputSchema/resultSchema 表示不接收对应数据；protocol 为空表示不能挑战。
     * @param revision 合同修订；变化后须重新批准绑定
     * @param phases 支持的回调阶段
     * @param configurationSchema 绑定配置的 JSON Schema
     * @param inputSchema 输入投影合同；空值表示不接收
     * @param resultSchema 成功结果投影合同；空值表示不接收
     * @param protocol 交互协议；空值表示不能挑战
     * @param resources 需要明确授权的资源声明
     */
    record Definition(String revision, Set<Phase> phases, String configurationSchema,
            String inputSchema, String resultSchema, PageInteraction protocol, List<PageResourceProfile> resources) { }

    /**
     * subject 为宿主摘要，correlation 是独立诊断身份；proof 仅交给所属 Hook。
     * result 仅在 SUCCEEDED 时投影；其它状态使用空对象，不公开异常或失败响应正文。
     *
     * @param operation 操作身份
     * @param endpoint 接口身份
     * @param phase 当前回调阶段
     * @param outcome 调用完成状态
     * @param correlation 独立诊断身份
     * @param subject 宿主提供的主体摘要
     * @param input 按合同投影的输入 JSON
     * @param result 成功结果投影；其它状态为空对象
     * @param configuration 经合同校验的绑定配置 JSON
     * @param attempt 本次验证尝试身份
     * @param revision 当前回调定义的合同修订
     * @param response 是否为挑战响应
     * @param proof 仅交给所属 Hook 的证明
     * @param http 宿主物化的 HTTP 元数据；本地调用可为空
     */
    record Invocation(String operation, String endpoint, Phase phase, Outcome outcome, String correlation,
            String subject, String input, String result, String configuration, String attempt,
            String revision, boolean response, String proof, Http http) { }

    /**
     * 宿主物化的 HTTP 元数据，不包含原始路径参数、查询或请求对象。
     * @param owner 目标接口所属 owner
     * @param handler 可信 handler 身份
     * @param method HTTP 方法
     * @param route 路由模板
     * @param shape 入口的同步、异步或流式形态
     * @param status HTTP 状态码
     * @param elapsedMs 请求耗时，单位毫秒
     * @param reason 受控完成原因
     */
    record Http(String owner, String handler, String method, String route, String shape,
            int status, long elapsedMs, String reason) { }

    /**
     * challenge 仅在 CHALLENGE 有效，必须符合声明的交互协议。
     * @param verdict 前置决策
     * @param challenge 受控挑战；仅在 CHALLENGE 时有效
     */
    record Decision(Verdict verdict, String challenge) {
        /** {@return 继续当前调用，不代表后续业务已成功} */
        public static Decision proceed() { return new Decision(Verdict.CONTINUE, ""); }
    }
}
