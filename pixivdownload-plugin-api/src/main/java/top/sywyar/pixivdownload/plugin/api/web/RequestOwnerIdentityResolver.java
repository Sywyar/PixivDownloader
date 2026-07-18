package top.sywyar.pixivdownload.plugin.api.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * 把当前 HTTP 请求解析为宿主信任的 owner 作用域。
 *
 * <p>契约只暴露结果；身份来源（宿主模式、管理员会话、cookie / 指纹等）由宿主实现统一判定。
 */
@FunctionalInterface
public interface RequestOwnerIdentityResolver {

    /** 解析当前请求；请求为 {@code null} 时实现应拒绝。 */
    RequestOwnerIdentity resolve(HttpServletRequest request);

    /**
     * 只查询宿主信任的、请求中已经存在的 owner UUID，绝不生成新的身份。
     *
     * <p>默认返回空，使未实现该能力的宿主与测试 lambda 保守拒绝依赖“已有身份”的操作。
     */
    default Optional<String> resolveExistingOwnerUuid(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return Optional.empty();
    }

    /**
     * 返回宿主已验证的邀请访客限流 subject；非邀请请求返回空。
     *
     * <p>subject 是只可用于相等性与计数的不透明字符串，不是授权结论，也不得被解析或展示。
     * 默认返回空，使旧宿主与测试 lambda 保持保守行为。
     */
    default Optional<String> resolveInvitedGuestRateLimitSubject(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return Optional.empty();
    }

    /**
     * 判断当前请求是否携带宿主认可的真实管理员认证会话。
     *
     * <p>该结果与 {@link RequestOwnerIdentity#admin()} 表达的管理员作用域不同：例如宿主可在
     * solo 模式下赋予请求管理员作用域，但不能据此伪造已经完成的管理员登录认证。
     * 默认返回 {@code false}，使未实现该能力的宿主与测试 lambda 保守拒绝认证豁免。
     */
    default boolean isAdminAuthenticated(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return false;
    }
}
