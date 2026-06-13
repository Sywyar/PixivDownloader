package top.sywyar.pixivdownload.plugin.api.work.service;

import top.sywyar.pixivdownload.plugin.api.work.model.WorkRestriction;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 作品可见性核心接口：访客邀请越界守卫的插件侧入口。
 *
 * <p>「访客」指携带访客邀请会话的请求；管理员 / 非访客请求一律视为无限制。
 * 三个方法均以请求为入参，访客会话由核心实现从请求上下文提取，插件不感知会话形态。
 */
public interface WorkVisibilityService {

    /**
     * 若当前请求是访客身份，校验单个作品是否在其可见范围内；越界抛 403 异常。
     * 非访客身份直接放行。
     */
    void requireVisible(HttpServletRequest request, WorkType workType, long workId);

    /**
     * 单作品可见性判定（列表逐项过滤用）。非访客身份恒为 {@code true}；
     * 访客身份下作品不存在或越界返回 {@code false}。
     */
    boolean isVisibleToGuest(HttpServletRequest request, WorkType workType, long workId);

    /**
     * 派生当前请求在指定媒体类型下的查询层限制条件；非访客身份返回 {@code null}（无限制）。
     */
    WorkRestriction restrictionFrom(HttpServletRequest request, WorkType workType);
}
