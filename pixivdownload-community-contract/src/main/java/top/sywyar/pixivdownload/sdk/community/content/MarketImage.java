package top.sywyar.pixivdownload.sdk.community.content;

import java.util.Map;

/** 图片的本地化替代文本及已冻结附件；旧目录可以只含审阅路径。 */
public record MarketImage(String path, Map<String, String> alt, MarketContent.Asset asset) {
    public MarketImage { alt = alt == null ? Map.of() : Map.copyOf(alt); }
}
