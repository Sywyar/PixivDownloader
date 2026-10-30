package top.sywyar.pixivdownload.plugin.api.web.page;

/**
 * 页面 owner 的必需区域，发布时必须恰好出现一次。
 * @param id 页面内稳定身份
 * @param labelKey 页面 namespace 内的名称翻译键
 * @param parent 最近的受控父区域，空字符串表示不得位于其它受控区域内
 * @param container 是否允许插入布局、文字与已获准的组件；叶区域的业务 DOM 不可改写
 * @param previewHidden 初始预览是否隐藏；编辑器可显式显示以编辑其它页面状态，不写入用户样式
 */
public record PageRegion(String id, String labelKey, String parent, boolean container, boolean previewHidden) { }
