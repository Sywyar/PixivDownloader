package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 可选的组件库分类，只影响编辑器展示，不改变组件或操作权限。
 * 分类随所属页面贡献一起撤回；未分组组件由宿主归入插件名称分类。
 * @param id 分类身份，插件声明必须为 owner:local-id；宿主默认分类使用 owner
 * @param labelKey 分类标题的 i18n key
 * @param namespace 标题及说明的 i18n namespace
 * @param descriptionKey 可选说明的 i18n key，无说明时为空
 * @param components 属于此分类的组件类型；非空、无重复且只能引用此 owner 声明的组件
 */
public record PageComponentCategory(String id, String labelKey, String namespace,
        String descriptionKey, List<String> components) {
    /**
     * 复制成员列表，避免贡献发布后被修改。
     * @param id 分类身份
     * @param labelKey 标题翻译键
     * @param namespace 翻译命名空间
     * @param descriptionKey 说明翻译键，可为空
     * @param components 组件类型列表
     */
    public PageComponentCategory { components = List.copyOf(components); }
}
