package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 页面 owner 声明可编辑正文及必须保留的区域；认证行为和默认回退继续归页面 owner。
 * @param id owner 命名空间页面身份
 * @param labelKey 页面名称翻译键
 * @param namespace 翻译命名空间
 * @param path 页面同源地址
 * @param publicPage 是否提供匿名只读投影
 * @param regions 必须保留的受控区域及父级约束
 * @param defaultProject 编辑器拥有的版本化 project JSON；空字符串使用区域列表生成最小布局
 * @param previewModuleUrl 同 owner 的只读区域预览模块，空字符串使用名称占位；使用组件 mount 生命周期
 * @param stylesheets 同 owner 的页面样式，在画布、预览和正式页面加载
 */
public record EditablePage(String id, String labelKey, String namespace, String path,
        boolean publicPage, List<PageRegion> regions, String defaultProject,
        String previewModuleUrl, List<String> stylesheets) {
    /**
     * 复制区域列表，声明快照不再依赖贡献者的可变集合。
     * @param id 页面身份
     * @param labelKey 名称翻译键
     * @param namespace 翻译命名空间
     * @param path 同源页面地址
     * @param publicPage 公开投影许可
     * @param regions 必需区域
     * @param defaultProject 编辑器 project JSON
     * @param previewModuleUrl 只读预览模块
     * @param stylesheets 页面样式
     */
    public EditablePage { regions = List.copyOf(regions); stylesheets = List.copyOf(stylesheets); }
}
