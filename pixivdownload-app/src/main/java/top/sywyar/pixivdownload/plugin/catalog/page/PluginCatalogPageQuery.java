package top.sywyar.pixivdownload.plugin.catalog.page;

/** 市场分页查询；所有文本都有界且仅被编码到已信任 catalog endpoint。 */
public record PluginCatalogPageQuery(String cursor, int limit, String query, String category,
                                     String publisher, String channel) {
    public PluginCatalogPageQuery {
        limit = limit <= 0 ? 24 : Math.min(limit, 100);
        cursor = bounded(cursor, 512, "cursor");
        query = bounded(query, 128, "query");
        category = bounded(category, 64, "category");
        publisher = bounded(publisher, 128, "publisher");
        channel = bounded(channel, 32, "channel");
    }

    public static PluginCatalogPageQuery first() {
        return new PluginCatalogPageQuery(null, 24, null, null, null, null);
    }

    public PluginCatalogPageQuery firstPage() {
        return new PluginCatalogPageQuery(null, limit, query, category, publisher, channel);
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is too long or contains controls");
        }
        return value;
    }
}
