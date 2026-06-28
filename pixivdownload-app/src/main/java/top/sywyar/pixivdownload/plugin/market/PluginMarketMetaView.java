package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.catalog.model.CatalogLink;
import top.sywyar.pixivdownload.plugin.catalog.model.CatalogPresentationToken;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogCategory;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogMarketMeta;

import java.util.List;
import java.util.Map;

/**
 * 市场视图中一个条目的<b>市场展示元数据</b>投影（纯展示 / 检索 / 排序，<b>不</b>参与安全决策）。在 DTO 边界对受控字段做
 * <b>净化</b>（防御性、与原始解析层「原样保留、消费方净化」契约一致，本视图即消费方）：分类经
 * {@link PluginCatalogCategory#resolve}（未知 → 实用工具）、图标 / 颜色 token 经 {@link CatalogPresentationToken}
 * （非法 → 回退）、主页外链经 {@link CatalogLink#sanitizeHttpUrl}（仅放行 http/https、挡 javascript:/data:/file:，否则 {@code null}）。
 * 故前端拿到的恒是安全可直接渲染的 token / 链接。
 *
 * @param displayName     展示名（{@code locale → 文本}，未安装浏览用的字面兜底）
 * @param summary         简短简介（{@code locale → 文本}，卡片用）
 * @param description     详细简介（{@code locale → 文本}，详情弹窗用）
 * @param author          作者 / 开发者展示名（可空）
 * @param sourceType      来源类型（{@code official} / {@code community}；可空）
 * @param category        已解析的分类 id（未知 → 实用工具回退）
 * @param tags            标签列表
 * @param homepageUrl     已净化的项目主页外链（非 http/https → {@code null}）
 * @param license         许可证标识（SPDX）
 * @param rating          平均评分（可空）
 * @param ratingCount     评分人数（可空）
 * @param downloadCount      当前版本下载量（可空）
 * @param previousDownloadCount  历史版本累积下载量（可空）
 * @param totalDownloadCount 累积总下载量（可空）
 * @param latestVersion      最新可用版本号（可空）
 * @param updatedTime     最近更新时间（ISO-8601，可空）
 * @param iconToken       已净化的展示图标受控 token
 * @param colorToken      已净化的展示强调色受控 token
 * @param recommended     是否官方推荐
 * @param officialRequired 是否官方标记为必备（仅展示，<b>不</b>等同核心必选插件策略）
 */
public record PluginMarketMetaView(
        Map<String, String> displayName,
        Map<String, String> summary,
        Map<String, String> description,
        String author,
        String sourceType,
        String category,
        List<String> tags,
        String homepageUrl,
        String license,
        Double rating,
        Integer ratingCount,
        Long downloadCount,
        Long previousDownloadCount,
        Long totalDownloadCount,
        String latestVersion,
        String updatedTime,
        String iconToken,
        String colorToken,
        boolean recommended,
        boolean officialRequired) {

    public PluginMarketMetaView {
        displayName = displayName != null ? Map.copyOf(displayName) : Map.of();
        summary = summary != null ? Map.copyOf(summary) : Map.of();
        description = description != null ? Map.copyOf(description) : Map.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
    }

    /** 把原始市场元数据投影为净化后的视图（分类 / token / 外链均经消费方净化）。 */
    static PluginMarketMetaView from(PluginCatalogMarketMeta meta) {
        if (meta == null) {
            return null;
        }
        return new PluginMarketMetaView(
                meta.displayName(),
                meta.summary(),
                meta.description(),
                meta.author(),
                meta.sourceType(),
                PluginCatalogCategory.resolve(meta.category()).id(),
                meta.tags(),
                CatalogLink.sanitizeHttpUrl(meta.homepageUrl()),
                meta.license(),
                meta.rating(),
                meta.ratingCount(),
                meta.downloadCount(),
                meta.previousDownloadCount(),
                meta.totalDownloadCount(),
                meta.latestVersion(),
                meta.updatedTime(),
                CatalogPresentationToken.sanitizeIcon(meta.iconToken()),
                CatalogPresentationToken.sanitizeColor(meta.colorToken()),
                meta.recommended(),
                meta.officialRequired());
    }
}
