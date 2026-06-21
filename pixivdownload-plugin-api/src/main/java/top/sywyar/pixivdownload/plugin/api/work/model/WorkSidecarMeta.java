package top.sywyar.pixivdownload.plugin.api.work.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品 meta sidecar（{@code {workId}.meta.json}）的解析视图：完整保存的 Pixiv 原始元数据中，
 * 那些「下载时不抓即永久丢失、且本地不可派生」的结构性字段（B 类）。由
 * {@link top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService#findSidecarMeta} 读出，
 * 供插件经统一入口取用，无需重抓 Pixiv。
 *
 * <p><b>纯 JDK 模型</b>（受 {@code PluginApiDependencyGuardTest} 守卫）：高价值 B 走 {@link Normalized}
 * 的 typed 访问器，长尾 B 走 {@link #raw()} 的通用原始节点读取（{@code Map}/{@code List}/{@code String}/
 * {@code Number}/{@code Boolean} 等 JDK 类型，<b>不暴露 Jackson {@code JsonNode}</b>）。解析只在实现侧
 * （{@code download} 包，可用 Jackson）发生，产出本 JDK-only 模型。
 *
 * <p>sidecar 是元数据的<b>权威落点</b>；可查询的 {@code upload_time}/{@code is_original} 列是它的
 * 可重建投影（经 {@link WorkMetadata} 读，不在本模型）。
 *
 * @param schemaVersion sidecar 顶层 schema 版本（从 1 起）
 * @param workType      媒体类型
 * @param workId        作品 ID
 * @param fetchedAt     捕获时刻（ISO-8601 文本；与下载落库时间 {@code time} 不同）
 * @param source        捕获来源：{@code schedule}（计划任务）/ {@code forward}（前端转发）/ {@code backfill}（历史回填）
 * @param normalized    高价值 B 的 typed 视图；缺失时为 {@code null}
 * @param raw           剪枝后的原始 body（去 C 类后的全 A+B），长尾 B 经此通用读取；防御性不可变浅拷贝
 */
public record WorkSidecarMeta(
        int schemaVersion,
        WorkType workType,
        long workId,
        String fetchedAt,
        String source,
        Normalized normalized,
        Map<String, Object> raw) {

    public WorkSidecarMeta {
        raw = raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /**
     * 高价值 B 的 typed 视图。各字段缺失 / Pixiv 未返回时为 {@code null}（{@code isOriginal} 三态：
     * {@code true}/{@code false}/{@code null}=未知）。
     *
     * @param uploadTime         真实上传时间（epoch 毫秒）
     * @param createTime         创建时间（epoch 毫秒，常等于 uploadTime）
     * @param reuploadTime       重传时间（epoch 毫秒，罕见）
     * @param isOriginal         原创标记三态
     * @param isUnlisted         是否未列出（私有链接作品）
     * @param titleTranslation   官方标题翻译（{@code titleCaptionTranslation.workTitle}）
     * @param captionTranslation 官方说明翻译（{@code titleCaptionTranslation.workCaption}）
     * @param illustComment      作者评论（仅当与 description 不同才捕获；相同则为 {@code null}）
     * @param pages              插画逐页尺寸与原图 URL（小说为空列表）
     */
    public record Normalized(
            Long uploadTime,
            Long createTime,
            Long reuploadTime,
            Boolean isOriginal,
            Boolean isUnlisted,
            String titleTranslation,
            String captionTranslation,
            String illustComment,
            List<Page> pages) {

        public Normalized {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    /**
     * 插画单页的尺寸与原图 URL（{@code width}/{@code height} 本地图片可派生；{@code original} 供恢复时免重抓）。
     *
     * @param width    页宽（像素）
     * @param height   页高（像素）
     * @param original 原图 URL
     */
    public record Page(int width, int height, String original) {
    }
}
