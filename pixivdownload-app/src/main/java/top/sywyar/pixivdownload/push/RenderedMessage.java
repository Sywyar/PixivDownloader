package top.sywyar.pixivdownload.push;

/**
 * 框架渲染产物——{@link PushFormatConverter} 把 {@link PushMessage} 协商 + 转换后交给通道的形态。
 * <p>
 * 与 {@link PushMessage}（业务入参，标记源格式）不同，{@code RenderedMessage} 的 {@link #body} 已经是
 * {@link #format} 对应的目标文本：通道<b>不再猜测</b>该用什么格式，只按 {@code format} 把 {@code body} /
 * {@code title} 拼成自身协议体。{@code format} 一定取自该通道
 * {@link PushChannel#supportedFormats()}，或在无可达格式时被框架强制降级为 {@link PushFormat#PLAIN_TEXT}。
 *
 * @param title  标题（已是目标格式下的文本；通道决定如何摆放，如作卡片 header）
 * @param body   正文（已渲染为 {@code format} 对应文本；{@link PushFormat#CARD} 时为 Markdown 内联文本）
 * @param format 本条消息已渲染成的格式
 * @param level  严重级别（透传，供通道映射到自身表现：卡片配色 / 字体色 / Bark level 等）
 */
public record RenderedMessage(String title, String body, PushFormat format, PushLevel level) {

    public RenderedMessage {
        if (title == null) {
            title = "";
        }
        if (body == null) {
            body = "";
        }
        if (format == null) {
            format = PushFormat.PLAIN_TEXT;
        }
        if (level == null) {
            level = PushLevel.INFO;
        }
    }
}
