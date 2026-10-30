package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 可选的多 owner 页面贡献。宿主按精确 publication 代理同步调用，撤回后拒绝调用并排空已进入的调用。
 * 配置校验和迁移归贡献者，不执行真实业务写操作；异常表示拒绝，不能解释为成功或改投替代者。
 */
public interface PageContribution {
    /** @return 此 owner 的全部组件 */
    default List<PageComponent> components() { return List.of(); }
    /** @return 此 owner 的可编辑页面 */
    default List<EditablePage> pages() { return List.of(); }
    /**
     * 在生成 Schema 校验之后执行额外业务校验，无额外约束时实现可直接返回。
     * @param type 已声明的组件类型
     * @param configuration 通过结构校验的配置
     */
    void validate(String type, String configuration);
    /**
     * 显式升级配置，默认拒绝；不自动更改持久数据。
     * @param type 组件类型
     * @param fromVersion 原配置版本
     * @param configuration 原配置
     * @return 当前配置版本的 JSON
     */
    default String migrate(String type, int fromVersion, String configuration) {
        throw new IllegalArgumentException("PAGE_CONFIGURATION_VERSION");
    }
}
