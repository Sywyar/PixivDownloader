package top.sywyar.pixivdownload.plugin.api.userscript;

import java.util.Objects;

/**
 * 宿主已物化的油猴脚本快照。
 *
 * <p>脚本文本与元数据来自同一次宿主刷新；本值不携带文件路径、资源句柄、ClassLoader 或 contribution owner。
 *
 * @param id 脚本的稳定标识
 * @param displayName 显示名称
 * @param description 描述
 * @param version 版本
 * @param content 已经物化的脚本文本
 * @param i18nNamespace 脚本所属翻译命名空间，空字符串表示仅使用既有翻译及元数据
 */
public record UserscriptArtifact(
        String id,
        String displayName,
        String description,
        String version,
        String content,
        String i18nNamespace
) {

    /**
     * 创建 {@code UserscriptArtifact} 实例。
     *
     * @param id 标识
     * @param displayName 显示名称
     * @param description 描述
     * @param version 版本
     * @param content 内容
     * @param i18nNamespace 脚本所属翻译命名空间，空字符串表示仅使用既有翻译及元数据
     */
    public UserscriptArtifact {
        requireText(id, "id");
        requireText(displayName, "displayName");
        description = description == null ? "" : description;
        version = version == null ? "" : version;
        content = Objects.requireNonNull(content, "content");
        i18nNamespace = Objects.requireNonNull(i18nNamespace, "i18nNamespace");
    }

    /**
     * 创建不带独立翻译命名空间的快照。
     * @param id 标识
     * @param displayName 默认显示名称
     * @param description 默认描述
     * @param version 版本
     * @param content 脚本内容
     */
    public UserscriptArtifact(String id, String displayName, String description, String version, String content) {
        this(id, displayName, description, version, content, "");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
