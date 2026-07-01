package top.sywyar.pixivdownload.push;

/**
 * 与具体通道无关的推送消息模型：承载 标题 + 正文 + 源格式 + 级别。这是整个推送框架的统一入参——业务侧只关心
 * "推什么"以及"正文是什么格式撰写的"，由 {@link PushFormatConverter} 协商出每个通道最合适的目标格式并自动
 * 转换，再交给各 {@link PushChannel} 渲染成自身协议体。
 * <p>
 * {@code sourceFormat} 只能是文本类的 {@link PushFormat#PLAIN_TEXT} / {@link PushFormat#MARKDOWN} /
 * {@link PushFormat#HTML}；{@link PushFormat#CARD} 是结构化目标、不可作源，传入时归一为
 * {@link PushFormat#MARKDOWN}。默认源格式为 {@link PushFormat#MARKDOWN}（最富表达、可向其余格式转换）。
 * <b>不</b>在此模型上烤死任何通道专属标记，以保持模型纯度与可复用性。
 *
 * @param title        标题
 * @param content      正文（按 {@code sourceFormat} 撰写）
 * @param sourceFormat 正文的源格式；{@code null} 归一为 {@link PushFormat#MARKDOWN}
 * @param level        严重级别；{@code null} 归一为 {@link PushLevel#INFO}
 */
public record PushMessage(String title, String content, PushFormat sourceFormat, PushLevel level) {

    public PushMessage {
        if (title == null) {
            title = "";
        }
        if (content == null) {
            content = "";
        }
        // CARD 不可作源；连同 null 一并归一为 MARKDOWN。
        if (sourceFormat == null || sourceFormat == PushFormat.CARD) {
            sourceFormat = PushFormat.MARKDOWN;
        }
        if (level == null) {
            level = PushLevel.INFO;
        }
    }

    /** 以 Markdown 源格式、{@link PushLevel#INFO} 级别构造（默认撰写格式）。 */
    public static PushMessage of(String title, String content) {
        return new PushMessage(title, content, PushFormat.MARKDOWN, PushLevel.INFO);
    }

    /** 以 Markdown 源格式、指定级别构造。 */
    public static PushMessage of(String title, String content, PushLevel level) {
        return new PushMessage(title, content, PushFormat.MARKDOWN, level);
    }

    /** 纯文本源格式。 */
    public static PushMessage text(String title, String content, PushLevel level) {
        return new PushMessage(title, content, PushFormat.PLAIN_TEXT, level);
    }

    /** Markdown 源格式。 */
    public static PushMessage markdown(String title, String content, PushLevel level) {
        return new PushMessage(title, content, PushFormat.MARKDOWN, level);
    }

    /** HTML 源格式。 */
    public static PushMessage html(String title, String content, PushLevel level) {
        return new PushMessage(title, content, PushFormat.HTML, level);
    }
}
