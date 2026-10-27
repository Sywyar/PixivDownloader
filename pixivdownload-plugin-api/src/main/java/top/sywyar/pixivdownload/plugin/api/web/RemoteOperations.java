package top.sywyar.pixivdownload.plugin.api.web;

import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;

/**
 * 当前 consumer publication 的生成操作入口；仅由生成的 HTTP 适配器调用。
 * 显式 POST / ADMIN 路由仍经宿主鉴权、同源写入与请求准入；publication owner 不代表用户身份。
 * 服务缺席、歧义或撤回返回 503 / REMOTE_UNAVAILABLE，业务失败返回 500 / REMOTE_FAILED；
 * 非法输入为 400、契约身份不符为 409、字节或工作预算超限为 413。
 * 请求和响应最多 1 MiB，值遍历最多 16384 节点、32 层；错误只含稳定码与本地化说明。
 * 不自动重试或转接 replacement；取消 HTTP 客户端不等于服务调用已退出。
 * @since 1.4.0
 */
public interface RemoteOperations {
    /**
     * 验证契约身份、路由、请求及响应后经受控服务代理执行一次，不重试业务调用。
     * @param service 已声明导入的服务
     * @param operation 源码操作名
     * @param identity 生成适配器的契约 SHA-256
     * @return 有界 JSON 结果或不含内部异常的机器错误
     * @throws top.sywyar.pixivdownload.plugin.api.service.ServiceAccessException 当前线程没有宿主 HTTP 请求时拒绝
     */
    Response invoke(ServiceKey<?> service, String operation, String identity);

    /**
     * @param status HTTP 状态
     * @param json UTF-8 JSON 文本
     * @param identity 当前契约身份
     */
    record Response(int status, String json, String identity) { }
}
