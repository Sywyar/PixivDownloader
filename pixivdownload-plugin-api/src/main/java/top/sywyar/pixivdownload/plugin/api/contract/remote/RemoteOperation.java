package top.sywyar.pixivdownload.plugin.api.contract.remote;

import java.lang.annotation.*;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

/** 一个请求 record 到一个响应 record 的同步操作，显式生成校验及客户端。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RemoteOperation {
    /** @return 不含通配符或变量的绝对 API 路径 */
    String path();
    /** @return 当前支持 POST，读取操作也使用 JSON 请求体 */
    HttpMethod method();
    /** @return 当前支持 ADMIN；鉴权由宿主在执行前完成 */
    AccessPolicy access();
}
