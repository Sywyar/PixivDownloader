package top.sywyar.pixivdownload.plugin.catalog.model;

import java.util.List;

/**
 * 一个 catalog 条目的<b>市场展示元数据</b>（纯展示 / 检索 / 排序用，<b>不</b>参与安全决策——安装仍只由包的 sha256 /
 * 大小 / 签名 / 描述符权威裁定）。承载高保真设计要求的卡片 / 详情字段，使 manifest 可驱动分类、搜索、排序与详情弹窗。
 *
 * <p>所有字段都来自服务端配置的受信目录清单，<b>绝不</b>来自请求参数。原始值在此<b>原样保留</b>（前向兼容、不丢数据）；
 * 分类未知 / 图标 / 颜色 token 的稳定回退由消费方经 {@link PluginCatalogCategory#resolve} / {@link CatalogPresentationToken}
 * 处理，不在解析期改写。纯 JDK record，<b>不入 {@code plugin-api}</b>。
 *
 * @param author        作者 / 开发者展示名（可空）
 * @param sourceType    来源类型（{@code official} / {@code community}；可空，未知按社区处理）
 * @param category      分类 id（可空 / 未知 → 由 {@link PluginCatalogCategory#resolve} 回退到实用工具）
 * @param tags          标签列表（检索 / 展示用）
 * @param rating        平均评分（可空，仅展示）
 * @param ratingCount   评分人数（可空，仅展示）
 * @param downloadCount 下载量（可空，仅展示 / 排序）
 * @param latestVersion 最新可用版本号（可空；与版本包列表配合标记「有更新」）
 * @param updatedTime   最近更新时间（ISO-8601 字符串，可空；仅展示 / 排序）
 * @param iconToken     展示图标受控 token（如 Font Awesome 字形 id；非法 / 空 → {@link CatalogPresentationToken#sanitizeIcon} 回退）
 * @param colorToken    展示强调色受控 token（如设计系统色名；非法 / 空 → {@link CatalogPresentationToken#sanitizeColor} 回退）
 * @param recommended   是否官方推荐（仅展示）
 * @param officialRequired 是否官方标记为必备 / 必选（仅展示，<b>不</b>等同核心必选插件策略）
 */
public record PluginCatalogMarketMeta(
        String author,
        String sourceType,
        String category,
        List<String> tags,
        Double rating,
        Integer ratingCount,
        Long downloadCount,
        String latestVersion,
        String updatedTime,
        String iconToken,
        String colorToken,
        boolean recommended,
        boolean officialRequired) {

    public PluginCatalogMarketMeta {
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
