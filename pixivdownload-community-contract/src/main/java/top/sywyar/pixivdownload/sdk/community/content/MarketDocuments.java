package top.sywyar.pixivdownload.sdk.community.content;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 原始字节与显示内容分离；所有资源必须由调用方按审核摘要读取后显式映射。 */
public final class MarketDocuments {
    public static final int RENDER_CHARACTERS = 16 * 1024 * 1024;
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Safelist SAFE = Safelist.none()
            .addTags("p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "code",
                    "ul", "ol", "li", "em", "strong", "del", "s", "table", "thead", "tbody", "tr", "th", "td",
                    "a", "img", "details", "summary", "div", "span", "sup", "sub", "kbd")
            .addAttributes("a", "href", "title").addAttributes("img", "src", "alt", "title")
            .addAttributes("th", "colspan", "rowspan").addAttributes("td", "colspan", "rowspan")
            .addProtocols("a", "href", "https", "http", "mailto", "#");

    private MarketDocuments() { }

    public static String text(byte[] bytes) {
        if (bytes.length > MarketContent.DOCUMENT_BYTES) {
            throw CommunityJson.limit("/content", MarketContent.DOCUMENT_BYTES, "bytes");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) throw new ContractException("SCHEMA_INVALID", "/content");
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        } catch (CharacterCodingException failure) { throw new ContractException("INVALID_UTF8", "/content"); }
    }

    private static Document parse(byte[] bytes, String format) {
        String source = text(bytes);
        if ("markdown".equals(format)) {
            var parser = Parser.builder().extensions(EXTENSIONS).maxOpenBlockParsers(64).build();
            source = HtmlRenderer.builder().extensions(EXTENSIONS).build().render(parser.parse(source));
        } else if (!"html".equals(format)) throw new ContractException("SCHEMA_INVALID", "/content/format");
        var result = Jsoup.parse(source);
        // 主动内容及其后代均不参与资源发现或正文显示。
        result.select("script,style,iframe,object,embed,form,input,button,textarea,select,svg,math,template,link,meta,base,video,audio,source").remove();
        return result;
    }

    public static List<String> images(byte[] bytes, String format) {
        var result = new LinkedHashSet<String>();
        for (var image : parse(bytes, format).select("img[src]")) {
            String value = image.attr("src");
            if (!value.isBlank()) result.add(value);
            if (result.size() > MarketContent.MAX_RESOURCES) {
                throw CommunityJson.limit("/content/resources", MarketContent.MAX_RESOURCES, "items");
            }
        }
        return List.copyOf(result);
    }

    public static String render(byte[] bytes, String format, Map<String, String> verifiedImages) {
        var source = parse(bytes, format);
        // 净化前移除所有远端资源；映射值只由宿主生成，绝不使用文档自报的 data URL。
        for (var image : source.select("img")) {
            String mapped = verifiedImages.get(image.attr("src"));
            if (mapped == null) { image.removeAttr("src"); }
            else image.attr("src", mapped);
        }
        var clean = new Cleaner(SAFE).clean(source);
        for (var link : clean.select("a[href]")) {
            link.attr("target", "_blank").attr("rel", "noopener noreferrer");
        }
        clean.outputSettings().prettyPrint(false);
        return clean.body().html(new BoundedHtml()).toString();
    }

    private static final class BoundedHtml implements Appendable {
        private final StringBuilder output = new StringBuilder();
        @Override public Appendable append(CharSequence value) { return append(value, 0, value.length()); }
        @Override public Appendable append(CharSequence value, int start, int end) {
            if ((long) output.length() + end - start > RENDER_CHARACTERS) {
                throw CommunityJson.limit("/content/render", RENDER_CHARACTERS, "characters");
            }
            output.append(value, start, end);
            return this;
        }
        @Override public Appendable append(char value) { return append(String.valueOf(value)); }
        @Override public String toString() { return output.toString(); }
    }
}
