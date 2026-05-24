package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.LayoutBase;
import top.sywyar.pixivdownload.common.AppInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 生成带颜色的 HTML 日志文件。
 *
 * <p>颜色定义与 {@link MdcColorConverter} 保持一致：
 * <ul>
 *   <li>INFO  → 绿色  {@code #4ade80}（ANSI GREEN_FG 32）</li>
 *   <li>WARN  → 黄色  {@code #facc15}（ANSI YELLOW_FG 33）</li>
 *   <li>ERROR → 红色  {@code #f87171}（ANSI RED_FG 31）</li>
 *   <li>DEBUG → 青色  {@code #22d3ee}（ANSI CYAN_FG 36）</li>
 *   <li>TRACE → 紫色  {@code #c084fc}（ANSI MAGENTA_FG 35）</li>
 * </ul>
 *
 * <p>输出为完整 HTML 文档，可直接在浏览器中查看。
 * 异常堆栈以可折叠的 {@code <details>} 块展示，避免撑开页面。
 */
public class HtmlLogLayout extends LayoutBase<ILoggingEvent> {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // ── 颜色定义（与 MdcColorConverter ANSI 色调对应） ──────────────────────────
    private static final String COLOR_INFO    = "#4ade80"; // ANSI 32 GREEN
    private static final String COLOR_WARN    = "#facc15"; // ANSI 33 YELLOW
    private static final String COLOR_ERROR   = "#f87171"; // ANSI 31 RED
    private static final String COLOR_DEBUG   = "#22d3ee"; // ANSI 36 CYAN
    private static final String COLOR_TRACE   = "#c084fc"; // ANSI 35 MAGENTA
    private static final String COLOR_DEFAULT = "#d4d4d4";

    // ── HTML 文档头部（包含 CSS） ────────────────────────────────────────────────

    @Override
    public String getPresentationHeader() {
        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s Log</title>
                <style>
                  * { box-sizing: border-box; margin: 0; padding: 0; }
                  body {
                    background: #1e1e1e;
                    color: #d4d4d4;
                    font-family: 'Cascadia Code', 'Consolas', 'Menlo', monospace;
                    font-size: 13px;
                    line-height: 1.6;
                    padding: 8px 12px;
                  }
                  .entry {
                    padding: 1px 4px;
                    border-radius: 2px;
                    white-space: pre-wrap;
                    word-break: break-all;
                  }
                  .entry:hover { background: #2a2a2a; }
                  .ts     { color: #6b7280; }
                  .lvl    { font-weight: bold; display: inline-block; width: 5ch; }
                  .thread { color: #60a5fa; }
                  .logger { color: #818cf8; }
                  .INFO   { color: %s; }
                  .WARN   { color: %S; }
                  .ERROR  { color: %S; }
                  .DEBUG  { color: %S; }
                  .TRACE  { color: %S; }
                  details { margin-top: 2px; }
                  details summary {
                    cursor: pointer;
                    color: #f87171;
                    font-size: 12px;
                    user-select: none;
                  }
                  details summary:hover { text-decoration: underline; }
                  .stack {
                    display: block;
                    padding: 4px 8px;
                    background: #2d1f1f;
                    border-left: 3px solid #f87171;
                    color: #fca5a5;
                    margin-top: 2px;
                    white-space: pre;
                    overflow-x: auto;
                    font-size: 12px;
                  }
                </style>
                </head>
                <body>
                """.formatted(
                java.util.Locale.getDefault().toLanguageTag(),
                AppInfo.NAME,
                COLOR_INFO, COLOR_WARN, COLOR_ERROR, COLOR_DEBUG, COLOR_TRACE);
    }

    @Override
    public String getPresentationFooter() {
        return "\n</body>\n</html>\n";
    }

    // ── 每条日志条目 ─────────────────────────────────────────────────────────────

    @Override
    public String doLayout(ILoggingEvent event) {
        String levelName = event.getLevel().toString();
        String timestamp = formatTimestamp(event.getTimeStamp());
        String msg       = escapeHtml(event.getFormattedMessage());
        String logger    = escapeHtml(abbreviateLogger(event.getLoggerName(), 36));
        String thread    = escapeHtml(event.getThreadName());

        StringBuilder sb = new StringBuilder(256);
        sb.append("<div class=\"entry ").append(levelName).append("\">");
        sb.append("<span class=\"ts\">").append(timestamp).append("</span> ");
        sb.append("<span class=\"lvl\">").append(levelName).append("</span> ");
        sb.append("<span class=\"thread\">[").append(thread).append("]</span> ");
        sb.append("<span class=\"logger\">").append(logger).append("</span> - ");
        sb.append(msg);

        // 异常堆栈（可折叠）
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            sb.append("\n<details><summary>").append(escapeHtml(tp.getClassName()));
            if (tp.getMessage() != null) {
                sb.append(": ").append(escapeHtml(tp.getMessage()));
            }
            sb.append("</summary><code class=\"stack\">");
            appendThrowable(sb, tp, 0);
            sb.append("</code></details>");
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    // ── 私有工具方法 ─────────────────────────────────────────────────────────────

    private static String formatTimestamp(long epochMillis) {
        return LocalDateTime
                .ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(FORMATTER);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 缩写包名：{@code top.sywyar.pixivdownload.gui.GuiLauncher} →
     * {@code t.s.p.g.GuiLauncher}（当长度超过 maxLen 时）。
     */
    private static String abbreviateLogger(String name, int maxLen) {
        if (name == null || name.length() <= maxLen) return name;
        String[] parts = name.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0)).append('.');
            }
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }

    /** 递归追加异常链（cause / suppressed）。 */
    private static void appendThrowable(StringBuilder sb, IThrowableProxy tp, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent)
          .append(escapeHtml(tp.getClassName()))
          .append(": ")
          .append(escapeHtml(tp.getMessage() == null ? "" : tp.getMessage()))
          .append("\n");

        for (StackTraceElementProxy step : tp.getStackTraceElementProxyArray()) {
            sb.append(indent).append("\tat ")
              .append(escapeHtml(step.getStackTraceElement().toString()))
              .append("\n");
        }

        if (tp.getCause() != null) {
            sb.append(indent).append("Caused by: ");
            appendThrowable(sb, tp.getCause(), depth);
        }
        for (IThrowableProxy sup : tp.getSuppressed()) {
            sb.append(indent).append("Suppressed: ");
            appendThrowable(sb, sup, depth + 1);
        }
    }

    /** 根据日志级别返回对应的 CSS 颜色（与 MdcColorConverter 一致）。 */
    @SuppressWarnings("unused") // 保留供外部工具 / 测试调用
    public static String colorForLevel(Level level) {
        return switch (level.levelInt) {
            case Level.INFO_INT  -> COLOR_INFO;
            case Level.WARN_INT  -> COLOR_WARN;
            case Level.ERROR_INT -> COLOR_ERROR;
            case Level.DEBUG_INT -> COLOR_DEBUG;
            case Level.TRACE_INT -> COLOR_TRACE;
            default              -> COLOR_DEFAULT;
        };
    }
}
