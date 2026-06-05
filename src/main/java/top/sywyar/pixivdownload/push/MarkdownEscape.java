package top.sywyar.pixivdownload.push;

/**
 * Markdown 字面量转义工具：把<b>数据值</b>里的 Markdown 内联元字符用反斜杠转义，使其在任何 Markdown
 * 渲染器（本项目的 {@link PushFormatConverter}，或各推送通道的厂商渲染器如企业微信 / 钉钉 / 飞书）中
 * 都按字面显示，而不会被当成强调 / 链接 / 行内代码等标记。
 * <p>
 * 典型场景：把 Cron 表达式 {@code 0 0 * * *}、含下划线的文件名 / id 等动态数据拼进 Markdown 正文时，
 * 裸 {@code *} / {@code _} 会与 Markdown 语法冲突而被渲染器吞掉（如企业微信群机器人会把 {@code * *}
 * 配对成强调、吃掉星号）。先经本类转义为 {@code 0 0 \* \* \*}，渲染器即按字面输出。
 * {@link PushFormatConverter} 在把 Markdown 转 PLAIN_TEXT / HTML 时会移除这些反斜杠转义（见其
 * {@code \\X} 处理），故 Bark / Telegram 等由本项目自行渲染的通道同样得到正确字面输出。
 * <p>
 * 只转义会造成<b>内联</b>结构冲突、且各渲染器普遍支持转义的字符：{@code \ ` * _ [ ]}；不转义仅在
 * 行首生效的 {@code # > - +} 等（数据值都嵌在行内，不在行首），以免给个别不支持这些转义的渲染器
 * 留下可见的反斜杠。
 */
public final class MarkdownEscape {

    /** 需转义的内联元字符；反斜杠自身在集合内，故 {@link #escape} 会把数据里的 {@code \} 转成 {@code \\}。 */
    private static final String SPECIAL = "\\`*_[]";

    private MarkdownEscape() {
    }

    /** 把 {@code literal} 中的 Markdown 内联元字符逐个加反斜杠转义；{@code null} 视作空串。 */
    public static String escape(String literal) {
        if (literal == null || literal.isEmpty()) {
            return literal == null ? "" : literal;
        }
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (SPECIAL.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
