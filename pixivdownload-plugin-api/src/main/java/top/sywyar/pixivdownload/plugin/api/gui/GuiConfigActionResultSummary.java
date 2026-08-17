package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI 配置动作结果使用的通用响应数组摘要声明。每个选中路径都必须指向准入的非敏感结构化标量；
 * 宿主限制条目数量和渲染后的纯文本长度。
 *
 * @param arrayPath 指向 JSON 数组的点分隔路径
 * @param labelPath 每个数组条目内用作前导标签的路径
 * @param statusPath 每个条目内用作状态 token 的可选路径
 * @param successStatus 摘要中忽略的成功状态值
 * @param detailPath 每个条目内用作详情文本的可选路径
 */
public record GuiConfigActionResultSummary(
        String arrayPath,
        String labelPath,
        String statusPath,
        String successStatus,
        String detailPath
) {

    /**
     * 规范化数组、标签、状态和详情路径。
     *
     * @param arrayPath 数组路径
     * @param labelPath 标签路径
     * @param statusPath 状态路径
     * @param successStatus 成功状态码
     * @param detailPath 详情路径
     */
    public GuiConfigActionResultSummary {
        arrayPath = arrayPath == null ? "" : arrayPath.trim();
        labelPath = labelPath == null ? "" : labelPath.trim();
        statusPath = statusPath == null ? "" : statusPath.trim();
        successStatus = successStatus == null ? "" : successStatus;
        detailPath = detailPath == null ? "" : detailPath.trim();
    }

    /**
     * 创建包含全部数组条目的摘要声明。
     *
     * @param arrayPath 指向 JSON 数组的点分隔路径
     * @param labelPath 条目标签路径
     * @param detailPath 条目详情路径
     * @return 全部条目摘要声明
     */
    public static GuiConfigActionResultSummary allItems(String arrayPath, String labelPath, String detailPath) {
        return new GuiConfigActionResultSummary(arrayPath, labelPath, "", "", detailPath);
    }

    /**
     * 创建只包含非成功条目的摘要声明。
     *
     * @param arrayPath 指向 JSON 数组的点分隔路径
     * @param labelPath 条目标签路径
     * @param statusPath 条目状态路径
     * @param successStatus 要忽略的成功状态值
     * @param detailPath 条目详情路径
     * @return 非成功条目摘要声明
     */
    public static GuiConfigActionResultSummary nonSuccessItems(String arrayPath, String labelPath,
                                                              String statusPath, String successStatus,
                                                              String detailPath) {
        return new GuiConfigActionResultSummary(arrayPath, labelPath, statusPath, successStatus, detailPath);
    }
}
