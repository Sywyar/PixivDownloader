package top.sywyar.pixivdownload.core.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作品标签：Pixiv 原始 {@code name} 加可选翻译 {@code translatedName}。
 * 仅用于宿主数据库边界的 {@code tags} / {@code artwork_tags} 结构映射；跨模块标签投影使用中性的
 * {@code core-api} {@code WorkTag}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDto {
    private Long tagId;
    private String name;
    private String translatedName;

    public TagDto(String name, String translatedName) {
        this(null, name, translatedName);
    }
}
