package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 页面 owner 声明可编辑正文及必须保留的区域；认证行为和默认回退继续归页面 owner。
 * @param id owner 命名空间页面身份
 * @param labelKey 页面名称翻译键
 * @param namespace 翻译命名空间
 * @param path 页面同源地址
 * @param publicPage 是否提供匿名只读投影；私有投影只允许已认证管理员且保留原路由访问限制
 * @param regions 必须保留的受控区域及父级约束
 * @param defaultProject 编辑器拥有的版本化 project JSON；空字符串使用区域列表生成最小布局
 * @param previewModuleUrl 同 owner 的只读区域预览模块，空字符串使用名称占位；使用组件 mount 生命周期
 * @param stylesheets 同 owner 的页面样式，在画布、预览和正式页面加载
 * @param targets owner 声明的操作目标，普通页面可为空
 */
public record EditablePage(String id, String labelKey, String namespace, String path,
        boolean publicPage, List<PageRegion> regions, String defaultProject,
        String previewModuleUrl, List<String> stylesheets, List<PageInteraction.Target> targets) {
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
     * @param targets 操作目标
     */
    public EditablePage {
        regions = List.copyOf(regions); stylesheets = List.copyOf(stylesheets); targets = List.copyOf(targets);
    }

    /** 合并已校验的关系；不能用页面配置重定向 owner 已声明的安全目标。
     * @param actions 管理员配置的动作关系
     * @param protocols 活动组件提供的交互协议
     * @return 保留 owner 声明并加入配置目标的纯值页面
     */
    public EditablePage withActions(List<PageInteraction.Action> actions, List<PageInteraction> protocols) {
        if (actions.size() > 128) throw new IllegalArgumentException("PAGE_ACTION_CAPACITY");
        var effective = new java.util.LinkedHashMap<String, PageInteraction.Target>();
        targets.forEach(target -> effective.put(target.id(), target));
        var ids = new java.util.HashSet<String>();
        var selectors = new java.util.HashSet<String>();
        for (var action : actions) {
            if (!ids.add(action.id()) || !selectors.add(action.formId() + "#" + action.actionId())
                    || regions.stream().noneMatch(region -> region.id().equals(action.region()) && region.container()))
                throw new IllegalArgumentException("PAGE_ACTION_INVALID");
            var existing = effective.get(action.id());
            if (existing != null) {
                if (!action.endpoint().isEmpty() || !existing.operation().equals(action.operation()) || !existing.region().equals(action.region()))
                    throw new IllegalArgumentException("PAGE_ACTION_FORBIDDEN");
            } else {
                effective.put(action.id(), new PageInteraction.Target(action.id(), "", action.operation().isEmpty() ? action.endpoint() : action.operation(),
                        action.region(), "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}", protocols, 16));
            }
        }
        return new EditablePage(id, labelKey, namespace, path, publicPage, regions, defaultProject, previewModuleUrl, stylesheets, List.copyOf(effective.values()));
    }
}
