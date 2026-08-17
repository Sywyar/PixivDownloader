package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * 单个 GUI 新手引导步骤的纯数据贡献。
 *
 * @param stepId 稳定步骤 ID
 * @param i18nNamespace 解析全部文本 key 使用的 namespace
 * @param titleKey 标题文本 key
 * @param bodyKey 正文文本 key
 * @param bulletKeys 要点文本 key
 * @param actionLabelKey 动作按钮文本 key
 * @param actionHref 动作目标 href
 * @param waitingKey 等待提示文本 key
 * @param completionKey 后端完成信号 key
 * @param order 贡献步骤之间的显示顺序
 */
public record GuiOnboardingStepContribution(
        String stepId,
        String i18nNamespace,
        String titleKey,
        String bodyKey,
        List<String> bulletKeys,
        String actionLabelKey,
        String actionHref,
        String waitingKey,
        String completionKey,
        int order
) {
    /**
     * 将缺失的要点列表规范化为空不可变列表。
     *
     * @param stepId 步骤标识
     * @param i18nNamespace 国际化命名空间
     * @param titleKey 标题键
     * @param bodyKey 正文键
     * @param bulletKeys 项目符号键列表
     * @param actionLabelKey 操作标签键
     * @param actionHref 操作链接
     * @param waitingKey 等待提示键
     * @param completionKey 完成提示键
     * @param order 排序值
     */
    public GuiOnboardingStepContribution {
        bulletKeys = bulletKeys == null ? List.of() : List.copyOf(bulletKeys);
    }
}
