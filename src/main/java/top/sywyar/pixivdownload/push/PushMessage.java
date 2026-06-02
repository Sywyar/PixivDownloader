package top.sywyar.pixivdownload.push;

/**
 * 与具体通道无关的推送消息模型：只承载 标题 + 正文 + 级别。这是整个推送框架的统一入参——业务侧只关心
 * "推什么"，由各 {@link PushChannel} 在发送时渲染成自身格式（Markdown / 卡片 / 纯文本）。
 * <p>
 * 正文为纯文本 / 轻量 Markdown（{@code \n} 换行）；通道若需要 HTML / 卡片结构，由通道实现自行转换，
 * <b>不</b>在此模型上烤死任何通道专属标记，以保持模型纯度与可复用性。
 *
 * @param title   标题（pushbot 模板中的 {@code ${1}}）
 * @param content 正文（pushbot 模板中的 {@code ${2}}）
 * @param level   严重级别；{@code null} 归一为 {@link PushLevel#INFO}
 */
public record PushMessage(String title, String content, PushLevel level) {

    public PushMessage {
        if (title == null) {
            title = "";
        }
        if (content == null) {
            content = "";
        }
        if (level == null) {
            level = PushLevel.INFO;
        }
    }

    /** 以 {@link PushLevel#INFO} 级别构造。 */
    public static PushMessage of(String title, String content) {
        return new PushMessage(title, content, PushLevel.INFO);
    }

    /** 指定级别构造。 */
    public static PushMessage of(String title, String content, PushLevel level) {
        return new PushMessage(title, content, level);
    }
}
