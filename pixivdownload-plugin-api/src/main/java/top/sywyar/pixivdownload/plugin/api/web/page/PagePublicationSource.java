package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.Optional;

/**
 * 可撤回的已发布页面只读来源。只提供渲染投影，不返回草稿或管理目录。
 * 宿主要求唯一活动来源；缺席、歧义、失败时调用方保留 owner 的默认页面。
 */
public interface PagePublicationSource {
    /**
     * 读取已发布渲染 JSON；未发布返回空 Optional。不得读取时创建默认数据。
     * @param pageId 已由页面 owner 声明的身份
     * @return 渲染投影 JSON，未发布时为空
     */
    Optional<String> published(String pageId);
}
