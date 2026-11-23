package top.sywyar.pixivdownload.plugin.api.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明 HTTP 处理器在路由过滤后执行的附加访问检查。处理器负责实施该检查。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface HttpApiAccess {
    /** {@return 处理器要求的附加访问条件} */
    Condition value();

    /** 处理器对非程序调用方的访问条件。 */
    enum Condition {
        /** 需要已认证的管理员会话。 */
        ADMIN_SESSION,
        /** solo 接受无邀请的调用方，multi 要求管理员会话。 */
        SOLO_OR_ADMIN,
        /** 通过路由检查后还须使用本机连接。 */
        LOCAL_ONLY,
        /** 要求 solo 模式和本机连接。 */
        SOLO_LOCAL_ONLY,
        /** 访问非公开页面需要管理员会话。 */
        PAGE_VISIBILITY,
        /** 读取当前管理员会话（若存在）。 */
        OPTIONAL_ADMIN,
        /** 读取当前管理员或邀请访客的会话（若存在）。 */
        OPTIONAL_SESSION
    }
}
