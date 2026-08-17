package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 声明式 GUI 配置动作结果规则使用的数据源。由响应派生的展示值仅限宿主准入的有界结构化标量；
 * 原始响应体、类似凭证的 key、原始错误和 HTML 均不能作为投影来源。
 */
public enum GuiConfigActionResultSource {
    /** GUI 端点是否可达。 */
    REACHABLE,
    /** HTTP 状态码是否为 2xx。 */
    HTTP_2XX,
    /** 整数形式的 HTTP 状态码。 */
    HTTP_STATUS,
    /** 格式化为简短展示文本的 HTTP 状态，例如 HTTP 500。 */
    HTTP_STATUS_TEXT,
    /** 从准入的非敏感 JSON 响应路径读取的有界标量。 */
    JSON,
    /** 从动作声明的响应数组中准入标量字段生成的有界纯文本摘要。 */
    SUMMARY
}
