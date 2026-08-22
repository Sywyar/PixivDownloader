package top.sywyar.pixivdownload.plugin.api.gui;

/** 与节点种类分开协商的稳定桌面渲染语义能力。 */
public enum DesktopUiCapability {
    /** 用户可以调整分栏尺寸。 */
    SPLIT_USER_RESIZABLE,
    /** 树分支可以展开和收起。 */
    TREE_EXPAND_COLLAPSE,
    /** 表格提供适合大量行的有界高度滚动。 */
    TABLE_LARGE_DATA_SCROLL,
    /** 数字输入保留数字校验语义。 */
    INPUT_NUMERIC,
    /** 日历日期输入保留日期语义。 */
    INPUT_TEMPORAL_DATE,
    /** 时刻输入保留时间语义。 */
    INPUT_TEMPORAL_TIME,
    /** 日期时间输入同时保留日期与时间语义。 */
    INPUT_TEMPORAL_DATE_TIME,
    /** 文件输入提供原生文件选择器。 */
    INPUT_PATH_FILE,
    /** 目录输入提供原生目录选择器。 */
    INPUT_PATH_DIRECTORY,
    /** 选择控件保留全部已选 id。 */
    SELECTION_MULTIPLE,
    /** 布局可按当前可用宽度自适应列数。 */
    LAYOUT_ADAPTIVE_GRID,
    /** 横向内容按固定页容量吸附并提供键盘与无障碍翻页。 */
    PAGED_ROW_SNAP_NAVIGATION,
    /** 整张语义表面可以作为单一命令区域激活。 */
    SURFACE_ACTIVATION,
    /** 图像可以裁剪为圆形边界。 */
    IMAGE_CIRCULAR_CLIP
}
