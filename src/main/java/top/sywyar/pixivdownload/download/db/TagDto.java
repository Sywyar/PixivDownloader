package top.sywyar.pixivdownload.download.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作品标签：Pixiv 原始 {@code name} 加可选翻译 {@code translatedName}。
 * 用作下载请求 {@code other.tags} 的元素、详情接口返回的标签列表项，以及 {@code tags} / {@code artwork_tags} 表的结构映射。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDto {
    private String name;
    private String translatedName;
}
