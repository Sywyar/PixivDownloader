package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 宿主聚合的活动目录，不是 PluginServices 的单 provider 服务。
 * 缺席组件不出现在快照中；校验固定 publication，撤回或换代明确失败。不得把 publication 持久化为业务配置。
 */
public interface PageCatalog {
    /** @return 所有活动组件的纯值快照 */
    List<AvailableComponent> components();
    /** @return 所有活动页面 */
    List<AvailablePage> pages();
    /**
     * 校验指定 publication 的当前版本配置。
     * @param type 组件类型
     * @param publication 目录返回的精确 publication
     * @param version 配置版本
     * @param configuration JSON 配置
     */
    void validate(String type, String publication, int version, String configuration);
    /**
     * 显式迁移并验证，调用者决定是否保存。
     * @param type 组件类型
     * @param publication 精确 publication
     * @param version 原配置版本
     * @param configuration 原配置
     * @return 当前版本配置
     */
    String migrate(String type, String publication, int version, String configuration);
    /**
     * 活动组件的值快照。
     * @param definition 组件声明
     * @param publication 精确发布身份
     */
    record AvailableComponent(PageComponent definition, String publication) { }
    /**
     * 页面声明与只读预览的精确发布身份；不持久化 publication。
     * @param definition 可信 owner 的页面声明
     * @param publication 撤回后不可复用的发布身份，核心页面绑定当前宿主进程
     */
    record AvailablePage(EditablePage definition, String publication) { }
}
