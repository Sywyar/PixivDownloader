package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.util.List;

/**
 * {@link ConfigSection} 与宿主 {@code ConfigPanel} 之间的窄接口：把 section 需要的共享能力
 * （字段注册表、取值 / 赋值、预设锁定、统一的 UI 助手与测试客户端）暴露出来，让特殊分组逻辑从
 * {@code ConfigPanel} 解耦到独立类，而加载 / 保存 / 校验 / 可见性重算仍集中在宿主。
 */
public interface ConfigSectionContext {

    // ── 字段表 ────────────────────────────────────────────────────────────────

    /** 全部字段的元数据快照（按当前 locale）。 */
    List<ConfigFieldSpec> allFields();

    /** 按 key 查字段元数据；不存在返回 null。 */
    ConfigFieldSpec findSpec(String key);

    /** 注册一个已渲染字段（纳入加载 / 保存 / 校验 / 可见性重算，并挂上变更监听）。 */
    void registerField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf);

    /** 依次渲染并注册一组字段到容器（左对齐 + 跟随间距，与普通分组渲染一致）。 */
    void addFields(JPanel content, List<ConfigFieldSpec> specs);

    /** 取某字段当前控件值；不存在返回空串。 */
    String currentFieldValue(String key);

    /** 设置某字段控件值（用于预设回填）。 */
    void setFieldValue(String key, String value);

    /** 预设锁定：字段当前可见时强制禁用其控件（在 {@link ConfigSection#afterEnabledStates()} 末尾叠加）。 */
    void lockField(String key);

    // ── UI 助手 ───────────────────────────────────────────────────────────────

    /** 新建一个纵向 BoxLayout 的可滚动内容面板（与普通分组同款）。 */
    JPanel newContentPanel();

    /** 让滚动面板首次显示时把视口重置回顶部（预设锁定会让视口偏离原点）。 */
    void resetScrollToTopOnFirstShow(JScrollPane sp);

    /** 「热重载 / 需重启」生效方式标记。 */
    JLabel effectLabel(boolean requiresRestart);

    /** 一个隐藏的校验错误占位 {@link JTextArea}（多复选框聚合字段用）。 */
    JTextArea hiddenValidationError();

    // ── 状态 / 动作 ───────────────────────────────────────────────────────────

    /** 底部提示条显示一条消息（10s 自动隐藏）。 */
    void showNotice(String msg);

    /** 触发一次全量可见 / 启用态重算（值变更或预设应用后调用）。 */
    void updateEnabledStates();

    /** 本地后端测试 / 热重载端点的统一 HTTP 客户端。 */
    GuiConfigTestClient testClient();
}
