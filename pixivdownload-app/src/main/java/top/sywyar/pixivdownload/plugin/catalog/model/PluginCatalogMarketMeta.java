package top.sywyar.pixivdownload.plugin.catalog.model;

import java.util.List;
import java.util.Map;

/**
 * 一个 catalog 条目的<b>市场展示元数据</b>（纯展示 / 检索 / 排序用，<b>不</b>参与安全决策——安装仍由包的 sha256 /
 * 大小 / 签名 / 描述符权威裁定）。承载高保真设计要求的卡片 / 详情字段，使 manifest 可驱动分类、搜索、排序与详情弹窗。
 *
 * <p><b>本地化文本（{@link #displayName} / {@link #summary} / {@link #description}）</b>：是
 * {@code locale → 文本} 映射（如 {@code {"zh": "统计", "en": "Statistics"}}）。与 {@code displayNameKey} /
 * {@code descriptionKey}（i18n key，见 {@code PluginCatalogEntry}）互补——<b>i18n key 仅在插件已安装、其 i18n 包已加载
 * 时才解析得出</b>，而市场浏览的是<b>未安装</b>插件，故须用这份字面文本兜底（社区插件尤其如此）。消费方解析顺序建议：
 * 能解析的 i18n key → 当前语言的字面文本 → 任一语言 → {@code pluginId}。
 *
 * <p>所有字段都来自服务端配置的受信目录清单，<b>绝不</b>来自请求参数。原始值在此<b>原样保留</b>（前向兼容、不丢数据）；
 * 分类未知 → {@link PluginCatalogCategory#resolve}、图标 / 颜色 token → {@link CatalogPresentationToken}、主页外链 →
 * {@link CatalogLink#sanitizeHttpUrl} 的稳定回退 / 净化由消费方处理，不在解析期改写。纯 JDK record，<b>不入 {@code plugin-api}</b>。
 *
 * @param displayName    展示名（{@code locale → 文本}，未安装浏览用的字面兜底）
 * @param summary        简短简介（{@code locale → 文本}，卡片用）
 * @param description    详细简介（{@code locale → 文本}，详情弹窗用）
 * @param author         作者 / 开发者展示名（可空）
 * @param sourceType     来源类型（{@code official} / {@code community}；可空，未知按社区处理）
 * @param category       分类 id（可空 / 未知 → 由 {@link PluginCatalogCategory#resolve} 回退到实用工具）
 * @param tags           标签列表（检索 / 展示用）
 * @param homepageUrl    项目主页 / 源码 / 文档链接（仅展示；渲染前须经 {@link CatalogLink#sanitizeHttpUrl} 仅放行 http/https）
 * @param license        许可证标识（如 SPDX {@code MIT} / {@code GPL-3.0}；仅展示）
 * @param rating         平均评分（可空，仅展示）
 * @param ratingCount    评分人数（可空，仅展示）
 * @param downloadCount  下载量（可空，仅展示 / 排序；由仓库服务端产出、本机只读）
 * @param latestVersion  最新可用版本号（可空；与版本包列表配合标记「有更新」）
 * @param updatedTime    最近更新时间（ISO-8601 字符串，可空；仅展示 / 排序）
 * @param iconToken      展示图标受控 token（非法 / 空 → {@link CatalogPresentationToken#sanitizeIcon} 回退）
 * @param colorToken     展示强调色受控 token（非法 / 空 → {@link CatalogPresentationToken#sanitizeColor} 回退）
 * @param recommended    是否官方推荐（仅展示）
 * @param officialRequired 是否官方标记为必备 / 必选（仅展示，<b>不</b>等同核心必选插件策略）
 */
public record PluginCatalogMarketMeta(
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
        String latestVersion,
        String updatedTime,
        String iconToken,
        String colorToken,
        boolean recommended,
        boolean officialRequired) {

    public PluginCatalogMarketMeta {
        displayName = displayName != null ? Map.copyOf(displayName) : Map.of();
        summary = summary != null ? Map.copyOf(summary) : Map.of();
        description = description != null ? Map.copyOf(description) : Map.of();
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
