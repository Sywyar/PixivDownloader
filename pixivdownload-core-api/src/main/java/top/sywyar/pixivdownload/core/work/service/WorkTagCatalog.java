package top.sywyar.pixivdownload.core.work.service;

/**
 * 共享作品标签字典的核心写入端口。
 *
 * <p>插画、文本等作品类型通过同一名称映射复用稳定标签标识。</p>
 */
public interface WorkTagCatalog {

    /**
     * 返回对应值。
     *
     * @param name 名称
     * @param translatedName 翻译后值名称
     * @return 方法返回的 {@code Long} 实例
     */
    Long getOrCreateTagId(String name, String translatedName);
}
