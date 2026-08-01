package top.sywyar.pixivdownload.core.work.model;

/**
 * 核心作品元数据中的文件名模板引用。
 *
 * @param catalogKey 共享模板目录的不透明兼容键；{@code null} 表示来源记录未保存目录键。
 *                   消费方不得从数值推导默认模板或对其执行数值运算
 * @param template   已解析的模板内容；目录项缺失或来源没有模板时可为 {@code null}
 */
public record WorkFileNameTemplateRef(Long catalogKey, String template) {
}
