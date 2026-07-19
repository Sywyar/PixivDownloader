package top.sywyar.pixivdownload.core.work.service;

import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;

/**
 * 作品可见性核心接口：按宿主签发的纯值作用域执行单作品访问校验。
 *
 * <p>身份和请求解析不属于本契约；调用方只传入 {@link WorkVisibilityScope}。无限制作用域直接放行，
 * 受限作用域按作品类型应用宿主既有的年龄分级、标签与作者规则。
 */
public interface WorkVisibilityService {

    /**
     * 校验单个作品是否在作用域内；无限制作用域直接放行。
     *
     * @throws WorkVisibilityDeniedException 受限作用域内作品不存在或不可见
     */
    void requireVisible(WorkVisibilityScope scope, WorkType workType, long workId);

    /**
     * 单作品可见性判定（列表逐项过滤用）。无限制作用域恒为 {@code true}；
     * 受限作用域内作品不存在或越界返回 {@code false}。
     */
    boolean isVisible(WorkVisibilityScope scope, WorkType workType, long workId);
}
