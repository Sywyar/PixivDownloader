package top.sywyar.pixivdownload.plugin.api.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 把当前 HTTP 请求解析为宿主信任的 owner 作用域。
 *
 * <p>契约只暴露结果；身份来源（宿主模式、管理员会话、cookie / 指纹等）由宿主实现统一判定。
 */
@FunctionalInterface
public interface RequestOwnerIdentityResolver {

    /** 解析当前请求；请求为 {@code null} 时实现应拒绝。 */
    RequestOwnerIdentity resolve(HttpServletRequest request);
}
