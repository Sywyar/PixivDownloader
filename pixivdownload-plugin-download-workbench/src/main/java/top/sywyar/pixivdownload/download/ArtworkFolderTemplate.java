package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作品目录模板渲染。
 *
 * <p>变量集与文件名模板完全一致（见 {@link PixivWorkFileNameFormatter}），但渲染结果被当作
 * <b>相对目录路径</b>处理：先按 {@code /}（或 {@code \}）分层，再对每一段做与文件名相同的安全清理
 * （非法字符替换、去掉结尾的点与空格、Windows 保留名加前缀）。
 *
 * <p>由于变量值本身已按文件名规则 sanitize（其中的 {@code /} 会变成 {@code _}），模板里的
 * {@code /} 是唯一的层次来源；{@code ..} 这类构造会被清理成空段并丢弃。调用方随后仍会用
 * {@code DownloadPathGuard} 复核路径确实落在下载根内。
 */
final class ArtworkFolderTemplate {

    private static final Pattern VARIABLES = Pattern.compile(
            "\\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\\+?|R18\\+?)}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\\\/]+");

    private ArtworkFolderTemplate() {}

    /**
     * 把作品目录模板渲染成下载根下的相对路径段。
     *
     * @return 有效路径段；模板为空、全空白或渲染后不含任何有效段时返回空列表，调用方应回退内置结构
     */
    static List<String> segments(String template,
                                 long artworkId,
                                 String artworkTitle,
                                 Long authorId,
                                 String authorName,
                                 long timestamp,
                                 int count,
                                 Boolean isAi,
                                 Integer xRestrict) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        String rendered = render(template, artworkId, artworkTitle, authorId, authorName,
                timestamp, count, isAi, xRestrict);
        List<String> segments = new ArrayList<>();
        for (String raw : SEPARATORS.split(rendered)) {
            String cleaned = PixivWorkFileNameFormatter.sanitize(raw);
            if (cleaned.isBlank() || ".".equals(cleaned) || "..".equals(cleaned)) {
                continue;
            }
            segments.add(cleaned);
        }
        return segments;
    }

    private static String render(String template,
                                 long artworkId,
                                 String artworkTitle,
                                 Long authorId,
                                 String authorName,
                                 long timestamp,
                                 int count,
                                 Boolean isAi,
                                 Integer xRestrict) {
        Matcher matcher = VARIABLES.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolve(
                    matcher.group(1), artworkId, artworkTitle, authorId, authorName,
                    timestamp, count, isAi, xRestrict)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String resolve(String variable,
                                  long artworkId,
                                  String artworkTitle,
                                  Long authorId,
                                  String authorName,
                                  long timestamp,
                                  int count,
                                  Boolean isAi,
                                  Integer xRestrict) {
        boolean ai = Boolean.TRUE.equals(isAi);
        int restrict = xRestrict == null ? 0 : xRestrict;
        return switch (variable) {
            case "artwork_id" -> String.valueOf(artworkId);
            case "artwork_title" -> PixivWorkFileNameFormatter.sanitize(artworkTitle);
            case "author_id" -> authorId == null ? "" : String.valueOf(authorId);
            case "author_name" -> PixivWorkFileNameFormatter.sanitize(authorName);
            case "timestamp" -> String.valueOf(timestamp);
            case "page" -> "0";
            case "count" -> String.valueOf(count);
            case "ai" -> ai ? "AI" : "";
            case "ai+" -> ai ? "AI" : "Human";
            case "R18" -> restrict == 2 ? "R18G" : restrict == 1 ? "R18" : "";
            case "R18+" -> restrict == 2 ? "R18G" : restrict == 1 ? "R18" : "SFW";
            default -> "";
        };
    }
}
