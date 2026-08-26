package top.sywyar.pixivdownload.gui.panel.configtab;

import javax.swing.JComponent;
import java.io.IOException;

/**
 * 配置面板里一个「特殊分组」标签页的可插拔实现：自带自定义控件 / 异步测试 / 预设联动，
 * 无法仅靠 {@code ConfigFieldSpec} 声明式渲染（普通字段平铺分组不需要实现本接口，直接由
 * {@code ConfigFieldRegistry} 声明名称 / 输入类型 / 提示即可）。
 * <p>
 * 由 {@link ConfigSectionContext} 提供共享的字段注册表、取值 / 赋值、提示与测试客户端等能力；
 * section 只负责构建自己这一页的 UI 并把字段注册进共享表。加载 / 保存 / 校验 / 可见性重算仍由宿主
 * {@code ConfigPanel} 统一驱动，新增 / 删除一个特殊页只改这里、不动宿主的派发循环。
 */
public interface ConfigSection {

    /** 本 section 负责的分组名（须与 {@code ConfigFieldRegistry.groups()} 中的某一项一致）。 */
    String group();

    /** 构建该分组标签页的内容，并在构建过程中把自身字段注册进共享字段表。 */
    JComponent build();

    /** 在每次 {@code updateEnabledStates()} 末尾回调，用于叠加预设锁定等分组私有的启用态规则。 */
    default void afterEnabledStates() {
    }

    /** 在配置值加载完成 / 重置为默认后回调，用于按当前值反查并应用服务商预设。 */
    default void onValuesLoaded() {
    }

    /**
     * 在宿主把标量字段写回 {@code config.yaml} 之后回调，用于持久化 section 自有的<b>非字段网格</b>状态
     * （如列表型配置）。默认无操作。
     *
     * @return 是否实际写入了改动（用于宿主判定保存提示是否需提示「重启生效」）
     * @throws IOException 持久化失败时抛出，由宿主统一弹错
     */
    default boolean onSave() throws IOException {
        return false;
    }
}
