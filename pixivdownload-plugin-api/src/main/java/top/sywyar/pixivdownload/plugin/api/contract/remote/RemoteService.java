package top.sywyar.pixivdownload.plugin.api.contract.remote;

import java.lang.annotation.*;

/**
 * 显式声明可生成远程操作的纯值服务；不授予 HTTP 权限或自动发布端点。
 * 须为公开顶层非泛型接口；操作输入和输出为本模块声明的 record，字段使用显式值 Schema 约束。
 * 远程整数同时受 Java 范围和 JavaScript 安全整数范围约束，long 须显式限制在
 * {@code -9007199254740991..9007199254740991} 内。可缺失字段必须可空，缺失解码为 null。
 * 拒绝未知字段、无效 Unicode、自定义构造器、访问器及序列化注解；不推导其含义。
 * 客户端运行期和服务端均执行校验，类型声明不能替代运行期边界。
 * @since 1.4.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RemoteService {
    /** @return 服务键的中性契约 owner */
    String owner();
}
