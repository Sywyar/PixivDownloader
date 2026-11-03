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

    /** @return 稳定 Hook 身份，实际提供者身份由宿主盖章 */
    String id();
    /** @return 纯值合同；合同变化必须更新 revision 并重新批准绑定 */
    Definition definition();
    /** @return 验证凭据等运行状态的代际，变化使旧挑战失效 */
    String generation();
    /**
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

    /** 空 inputSchema/resultSchema 表示不接收对应数据；protocol 为空表示不能挑战。 */
    record Definition(String revision, Set<Phase> phases, String configurationSchema,
            String inputSchema, String resultSchema, PageInteraction protocol, List<PageResourceProfile> resources) { }

    /**
     * subject 为宿主摘要，correlation 是独立诊断身份；proof 仅交给所属 Hook。
     * result 仅在 SUCCEEDED 时投影；其它状态使用空对象，不公开异常或失败响应正文。
     */
    record Invocation(String operation, String endpoint, Phase phase, Outcome outcome, String correlation,
            String subject, String input, String result, String configuration, String attempt,
            String revision, boolean response, String proof, Http http) { }

    /** 宿主物化的 HTTP 元数据，不包含原始路径参数、查询或请求对象。 */
    record Http(String owner, String handler, String method, String route, String shape,
            int status, long elapsedMs, String reason) { }

    /** challenge 仅在 CHALLENGE 有效，必须符合声明的交互协议。 */
    record Decision(Verdict verdict, String challenge) {
        /** @return 继续当前调用，不代表后续业务已成功 */
        public static Decision proceed() { return new Decision(Verdict.CONTINUE, ""); }
    }
}
